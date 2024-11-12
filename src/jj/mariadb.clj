(ns jj.mariadb
  (:require
    [clojure.tools.logging :as logger])
  (:import (ch.vorburger.mariadb4j DB DBConfiguration DBConfigurationBuilder)))

(def ^:private db-atom (atom nil))

(defn- get-db [port
               delete-after-shutdown
               base-dir
               data-dir
               security-disabled]
  (let [db-config ^DBConfiguration (-> (DBConfigurationBuilder/newBuilder)
                                       (.setPort port)
                                       (.setDeletingTemporaryBaseAndDataDirsOnShutdown delete-after-shutdown)
                                       (.setDataDir base-dir)
                                       (.setBaseDir data-dir)
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
  - port
  - base-dir
  - data-dir
  - security-disabled"
  [{:keys [port
           delete-after-shutdown
           base-dir
           data-dir
           security-disabled]
    :or   {port                  4306
           delete-after-shutdown true
           base-dir              (str (System/getProperty "java.io.tmpdir") "/maria-base")
           data-dir              (str (System/getProperty "java.io.tmpdir") "/maria-data")
           security-disabled     true}}]
  (when (is-running?) (halt-db!))

  (let [db (get-db port
                   delete-after-shutdown
                   base-dir
                   data-dir
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
  - port
  - base-dir
  - data-dir
  - security-disabled
  - on-error"
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
        db (get-db port
                   delete-after-shutdown
                   base-dir
                   data-dir
                   security-disabled)]
    (try
      (.start db)
      (reset! db-atom db)
      (f)
      (catch Exception e
        (reset! runtime-exception e)
        (when-not (nil? on-error)
          (on-error))
        (logger/error (.getMessage ^Exception e)))
      (finally
        (halt-db!)))))