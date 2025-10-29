(ns embedded.mariadb
  (:require
    [clojure.tools.logging :as logger])
  (:import (ch.vorburger.mariadb4j DB DBConfiguration DBConfigurationBuilder)
           (java.io File)))

(defonce shutdown-threads (atom {}))
(def ^:dynamic *db-spec* nil)

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
  [^DB db]
  (when (not (nil? db))
    (let [db ^DB db
          shutdown-thread (get @shutdown-threads db)]
      (.stop ^DB db)
      (when shutdown-thread
        (try
          (.removeShutdownHook (Runtime/getRuntime) shutdown-thread)
          (catch IllegalStateException _
            nil))
        (swap! shutdown-threads dissoc db)))))

(defn- create-shutdown-thread [^DB db]
  (Thread. (fn []
             (when db
               (.stop ^DB db)
               (swap! shutdown-threads dissoc db)))))


(defn init-db
  "Starts a temporary MariaDB instance and returns it.

  Available options to configure DB:
  - port (int): Maria db port
  - base-dir (String): Path where maria db files are stored
  - base-dir (File): File in which maria db  files are stored
  - data-dir (String): Path where maria db data is stored
  - data-dir (File): File where maria db data is stored
  - security-disabled (Boolean):  Skip grant tables"
  [{:keys [port
           delete-after-shutdown
           base-dir
           data-dir
           security-disabled
           dbname]
    :or   {port                  4306
           delete-after-shutdown true
           base-dir              (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-data1"))
           data-dir              (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-base1"))
           security-disabled     true
           dbname                "testdb"}}]

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
        db-url (.getURL db-conf dbname)
        db ^DB (DB/newEmbeddedDB db-conf)]
    (try
      (binding [*db-spec* {:jdbcUrl db-url}]
        (.start db))

      (let [shutdown-thread (create-shutdown-thread db)]
        (.addShutdownHook (Runtime/getRuntime) shutdown-thread)
        (swap! shutdown-threads assoc db shutdown-thread))
      (catch Exception e
        (logger/error (.getMessage ^Exception e))))
    db))



(defn with-db
  "Starts a temporary MariaDB instance and executes the provided function `f` with database being available and ensures that database shuts down properly after function `f` is finished.

  Available options to configure DB:
  - port (int): Maria db port
  - base-dir (String): Path where maria db files are stored
  - base-dir (File): File in which maria db  files are stored
  - data-dir (String): Path where maria db data is stored
  - data-dir (File): File where maria db data is stored
  - security-disabled (Boolean):  Skip grant tables
  - on-error (IFn): Function to run when error is thrown."
  [f {:keys [port
             delete-after-shutdown
             base-dir
             data-dir
             security-disabled
             on-error
             dbname
             username
             password
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
             init-hook             nil}}]
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

      (let [db-spec {:jdbcUrl db-url}]
        (binding [*db-spec* db-spec]
          (f)))

      (catch Exception e
        (reset! runtime-exception e)
        (when-not (nil? on-error)
          (on-error e))
        (logger/error (.getMessage ^Exception e)))
      (finally
        (halt-db db)))))