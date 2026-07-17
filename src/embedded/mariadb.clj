(ns embedded.mariadb
  (:require
    [clojure.java.shell :as shell]
    [clojure.string :as string]
    [clojure.tools.logging :as logger])
  (:import (ch.vorburger.mariadb4j DB DBConfiguration DBConfigurationBuilder)
           (java.io File)))

(defonce ^:private shutdown-threads (atom {}))

(defrecord DockerDB [container-id port dbname])

(defn- stop-db [db]
  (if (instance? DockerDB db)
    (shell/sh "docker" "stop" ^String (:container-id db))
    (.stop ^DB db)))

(defn- docker-cli [& args]
  (let [{:keys [exit out err]} (apply shell/sh "docker" args)]
    (when-not (zero? exit)
      (logger/error (format "docker %s failed: %s" (string/join " " args) err))
      (throw (ex-info (str "docker " (first args) " failed") {:args args
                                                             :exit exit
                                                             :err  err})))
    (string/trim out)))

(defn- container-running? [container-id]
  (= "true" (string/trim (str (:out (shell/sh "docker" "inspect" "-f" "{{.State.Running}}" container-id))))))

(defn- wait-for-container [container-id timeout]
  (let [deadline (+ (System/currentTimeMillis) ^long timeout)]
    (loop []
      (let [{:keys [exit]} (shell/sh "docker" "exec" container-id
                                     "healthcheck.sh" "--connect" "--innodb_initialized")]
        (when-not (zero? exit)
          (cond
            (not (container-running? container-id))
            (throw (ex-info "MariaDB container exited unexpectedly"
                            {:container-id container-id
                             :logs         (:err (shell/sh "docker" "logs" container-id))}))

            (< deadline (System/currentTimeMillis))
            (throw (ex-info "MariaDB container did not become ready before timeout"
                            {:container-id container-id
                             :timeout      timeout}))

            :else
            (do
              (Thread/sleep 500)
              (recur))))))))

(defn- host-utc-offset
  []
  (let [id (.getId (.getOffset (java.time.OffsetDateTime/now)))]
    (if (= "Z" id) "+00:00" id)))

(defn- start-docker-db [{:keys [port dbname docker-image startup-timeout in-memory]}]
  (let [container-id (apply docker-cli
                            (concat ["run" "--detach" "--rm"
                                     "--publish" (str port ":3306")
                                     "--env" "MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1"
                                     "--env" (str "MARIADB_DATABASE=" dbname)]
                                    (when in-memory
                                      ["--tmpfs" "/var/lib/mysql"])
                                    [docker-image
                                     "--character-set-server=utf8mb4"
                                     "--collation-server=utf8mb4_general_ci"
                                     (str "--default-time-zone=" (host-utc-offset))]))]
    (try
      (wait-for-container container-id startup-timeout)
      (->DockerDB container-id port dbname)
      (catch Exception e
        (shell/sh "docker" "stop" container-id)
        (throw e)))))

(defn- get-db [port
               delete-after-shutdown
               base-dir-file
               data-dir-file
               security-disabled]
  (let [db-config ^DBConfiguration (-> (DBConfigurationBuilder/newBuilder)
                                       (.setPort port)
                                       (.setDeletingTemporaryBaseAndDataDirsOnShutdown delete-after-shutdown)
                                       (.setDataDir data-dir-file)
                                       (.setBaseDir base-dir-file)
                                       (.setSecurityDisabled security-disabled)
                                       .build)]
    db-config))

(defn halt-db
  [db]
  (when (not (nil? db))
    (let [shutdown-thread (get @shutdown-threads db)]
      (stop-db db)
      (when shutdown-thread
        (try
          (.removeShutdownHook (Runtime/getRuntime) shutdown-thread)
          (catch IllegalStateException _
            nil))
        (swap! shutdown-threads dissoc db)))))

(defn- create-shutdown-thread [db]
  (Thread. (fn []
             (when db
               (stop-db db)
               (swap! shutdown-threads dissoc db)))))


(defn init-db
  "Starts a temporary MariaDB instance and returns it.

  Available options to configure DB:
  - port (int): Maria db port
  - base-dir (String): Path where maria db files are stored
  - base-dir (File): File in which maria db  files are stored
  - data-dir (String): Path where maria db data is stored
  - data-dir (File): File where maria db data is stored
  - security-disabled (Boolean):  Skip grant tables
  - containerized (Boolean): Run MariaDB in a Docker container instead of embedded, publishing `port` on localhost
  "
  [{:keys [port
           delete-after-shutdown
           base-dir
           data-dir
           security-disabled
           dbname
           containerized
           docker-image
           startup-timeout
           in-memory]
    :or   {port                  4306
           delete-after-shutdown true
           base-dir              (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-data1"))
           data-dir              (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-base1"))
           security-disabled     true
           dbname                "testdb"
           containerized         false
           docker-image          "mariadb:latest"
           startup-timeout       120000
           in-memory             false}}]

  (if containerized
    (try
      (let [db (start-docker-db {:port            port
                                 :dbname          dbname
                                 :docker-image    docker-image
                                 :startup-timeout startup-timeout
                                 :in-memory       in-memory})
            shutdown-thread (create-shutdown-thread db)]
        (.addShutdownHook (Runtime/getRuntime) shutdown-thread)
        (swap! shutdown-threads assoc db shutdown-thread)
        db)
      (catch Exception e
        (logger/error (.getMessage ^Exception e))))

    (let [base-dir-file (cond
                        (instance? String base-dir)
                        (File. ^String base-dir)

                        (instance? File base-dir)
                        base-dir

                        :else
                        (do
                          (logger/error "Unsupported file type")
                          (throw (Exception. "Unsupported file type"))))

        data-dir-file (cond
                        (instance? String data-dir)
                        (File. ^String data-dir)

                        (instance? File data-dir)
                        data-dir

                        :else
                        (do
                          (logger/error "Unsupported file type")
                          (throw (Exception. "Unsupported file type"))))

        db-conf ^DBConfiguration (get-db port
                                         delete-after-shutdown
                                         base-dir-file
                                         data-dir-file
                                         security-disabled)
        db ^DB (DB/newEmbeddedDB db-conf)]
    (try
      (.start db)

      (let [shutdown-thread (create-shutdown-thread db)]
        (.addShutdownHook (Runtime/getRuntime) shutdown-thread)
        (swap! shutdown-threads assoc db shutdown-thread))
      (catch Exception e
        (logger/error (.getMessage ^Exception e))))
    db)))



(defn with-db
  "Starts a temporary MariaDB instance and executes the provided function `f`, passing it a db-spec map ({:jdbcUrl ...}) pointing at the running database, and ensures that database shuts down properly after function `f` is finished.

  Available options to configure DB:
  - port (int): Maria db port
  - base-dir (String): Path where maria db files are stored
  - base-dir (File): File in which maria db  files are stored
  - data-dir (String): Path where maria db data is stored
  - data-dir (File): File where maria db data is stored
  - security-disabled (Boolean):  Skip grant tables
  - on-error (IFn): Function to run when error is thrown.
  - containerized (Boolean): Run MariaDB in a Docker container instead of embedded, publishing `port` on localhost
  "
  [f {:keys [port
             delete-after-shutdown
             base-dir
             data-dir
             security-disabled
             on-error
             dbname
             username
             password
             containerized
             docker-image
             startup-timeout
             in-memory
             init-hook]
      :or   {port                  4306
             delete-after-shutdown true
             base-dir              (str (System/getProperty "java.io.tmpdir") "/maria-base")
             data-dir              (str (System/getProperty "java.io.tmpdir") "/maria-data")
             security-disabled     true
             on-error              nil
             dbname                "testdb"
             username              "username"
             password              "password"
             containerized         false
             docker-image          "mariadb:latest"
             startup-timeout       120000
             in-memory             false
             init-hook             nil}}]
  (if containerized
    (try
      (let [db (start-docker-db {:port            port
                                 :dbname          dbname
                                 :docker-image    docker-image
                                 :startup-timeout startup-timeout
                                 :in-memory       in-memory})
            shutdown-thread (create-shutdown-thread db)]
        (try
          (.addShutdownHook (Runtime/getRuntime) shutdown-thread)
          (swap! shutdown-threads assoc db shutdown-thread)
          (f {:jdbcUrl (format "jdbc:mariadb://localhost:%s/%s?user=root" port dbname)})
          (finally
            (halt-db db))))
      (catch Exception e
        (when-not (nil? on-error)
          (on-error e))
        (logger/error (.getMessage ^Exception e))))

    (let [runtime-exception (atom nil)
        base-dir-file (cond
                        (instance? String base-dir)
                        (File. ^String base-dir)

                        (instance? File base-dir)
                        base-dir

                        :else
                        (do
                          (logger/error "Unsupported file type")
                          (throw (Exception. "Unsupported file type"))))

        data-dir-file (cond
                        (instance? String data-dir)
                        (File. ^String data-dir)

                        (instance? File data-dir)
                        data-dir

                        :else
                        (do
                          (logger/error "Unsupported file type")
                          (throw (Exception. "Unsupported file type"))))

        db-conf ^DBConfiguration (get-db port
                                         delete-after-shutdown
                                         base-dir-file
                                         data-dir-file
                                         security-disabled)
        db-url (.getURL db-conf dbname)
        db ^DB (DB/newEmbeddedDB db-conf)

        shutdown-thread (create-shutdown-thread db)
        ]

    (try
      (.start db)
      (.addShutdownHook (Runtime/getRuntime) shutdown-thread)
      (swap! shutdown-threads assoc db shutdown-thread)
      (.run db "DROP DATABASE IF EXISTS test;")
      (.run db (format "CREATE DATABASE %s CHARACTER SET utf8mb4\nCOLLATE utf8mb4_general_ci; ;" dbname))

      (f {:jdbcUrl db-url})

      (catch Exception e
        (reset! runtime-exception e)
        (when-not (nil? on-error)
          (on-error e))
        (logger/error (.getMessage ^Exception e)))
      (finally
        (halt-db db))))))
