(ns embedded.mariadb
  (:require
    [clojure.tools.logging :as logger])
  (:import (ch.vorburger.mariadb4j DB DBConfiguration DBConfigurationBuilder)
           (java.io File)))

(def ^:private db-atom (atom nil))

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
    (-> (DB/newEmbeddedDB db-config))))

(defn halt-db!
  "Stops database, if it is running." []
  (when (not (nil? @db-atom))
    (let [db ^DB @db-atom]
      (.stop ^DB db))
    (reset! db-atom nil)))

(defn is-running?
  "Checks if the database is currently running.
  returns true if running, and false if not."
  []
  (not (nil? @db-atom)))

(defn init-db!
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
           security-disabled]
    :or   {port                  4306
           delete-after-shutdown true
           base-dir              (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-data1"))
           data-dir              (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-base1"))
           security-disabled     true}}]
  (when (is-running?) (halt-db!))

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
        db ^DB (get-db port
                       delete-after-shutdown
                       base-dir-file
                       data-dir-file
                       security-disabled)]
    (try
      (.start db)
      (reset! db-atom db)
      (catch Exception e
        (logger/error (.getMessage ^Exception e))))
    db))

(defn with-db!
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
             on-error]
      :or   {port                  4306
             delete-after-shutdown true
             base-dir              (str (System/getProperty "java.io.tmpdir") "/maria-base")
             data-dir              (str (System/getProperty "java.io.tmpdir") "/maria-data")
             security-disabled     true
             on-error              nil}}]
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
        db ^DB (get-db port
                       delete-after-shutdown
                       base-dir-file
                       data-dir-file
                       security-disabled)]
    (try
      (.start db)
      (reset! db-atom db)
      (f)
      (catch Exception e
        (reset! runtime-exception e)
        (when-not (nil? on-error)
          (on-error e))
        (logger/error (.getMessage ^Exception e)))
      (finally
        (halt-db!)))))