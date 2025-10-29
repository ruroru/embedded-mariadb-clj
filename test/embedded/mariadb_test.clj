(ns embedded.mariadb-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [embedded.mariadb :as mariadb]
    [next.jdbc :as jdbc])
  (:import (ch.vorburger.mariadb4j DB)
           (java.io File)))

(def mariadb-port 4308)
(def db-spec {:dbtype "mariadb"
              :host   "localhost"
              :port   mariadb-port})

(def ^:private query ["SELECT schema_name FROM information_schema.SCHEMATA;"])
(def ^:private db-name "my_database")

(defn assert-databases []
  (jdbc/execute! (jdbc/get-datasource db-spec) [(format "CREATE DATABASE %s;" db-name)])
  (is (contains? (into #{}
                       (jdbc/execute! (jdbc/get-datasource db-spec) query))
                 #:SCHEMATA{:schema_name db-name})))

(deftest test-maria-db-init-mariadb-with-default-base-and-data-dirs
  (let [mariadb (mariadb/init-db {:port mariadb-port})]
    (is (instance? DB mariadb))
    (mariadb/halt-db mariadb)))


(deftest test-maria-db-init-mariadb-can-use-file-for-base-and-data-dirs
  (let [mariadb (mariadb/init-db {:port     mariadb-port
                                  :base-dir (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-data2"))
                                  :data-dir (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-base2"))})]
    (is (instance? DB mariadb))
    (mariadb/halt-db mariadb)))


(deftest test-maria-db-init-mariadb-can-use-string-for-base-and-data-dirs
  (let [mariadb (mariadb/init-db {:port     mariadb-port
                                  :base-dir (str (System/getProperty "java.io.tmpdir") "/maria-base3")
                                  :data-dir (str (System/getProperty "java.io.tmpdir") "/maria-data3")})]
    (is (instance? DB mariadb))
    (mariadb/halt-db mariadb)
    (try
      (jdbc/execute! (jdbc/get-datasource {:dbtype   "mariadb",
                                           :host     "localhost",
                                           :port     mariadb-port,
                                           :dbname   "testdb",
                                           :password "password",
                                           :username "username"})
                     ["SHOW DATABASES;"])

      (is (= -1 -2) "should fail")
      (catch Exception e
        (is (str/includes? (.getMessage e) "Socket fail to connect to localhost:4308")
            )))))


(deftest test-with-mariadb-default-base-and-data-dirs
  (mariadb/with-db assert-databases {:port mariadb-port}))


(deftest test-with-mariadb-can-use-file-for-base-and-data-dirs
  (mariadb/with-db assert-databases {:port     mariadb-port
                                     :base-dir (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-data2"))
                                     :data-dir (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-base2"))}))


(deftest test-with-mariadb-can-use-string-for-base-and-data-dirs
  (mariadb/with-db assert-databases {:port     mariadb-port
                                     :base-dir (str (System/getProperty "java.io.tmpdir") "/maria-base3")
                                     :data-dir (str (System/getProperty "java.io.tmpdir") "/maria-data3")}))

(deftest test-default-db-spec
  (mariadb/with-db (fn []
                     (is (= #{#:SCHEMATA{:Database "information_schema"}
                              #:SCHEMATA{:Database "mysql"}
                              #:SCHEMATA{:Database "performance_schema"}
                              #:SCHEMATA{:Database "sys"}
                              #:SCHEMATA{:Database "testdb"}}
                            (into #{}
                                  (jdbc/execute! (jdbc/get-datasource {
                                                                       :dbtype   "mariadb"
                                                                       :host     "localhost"
                                                                       :password "password1"
                                                                       :port     4306
                                                                       :username "username1"})
                                                 ["SHOW DATABASES;"]))))
                     (is (= {:jdbcUrl "jdbc:mariadb://localhost:4306/testdb"}
                            mariadb/*db-spec*
                            )))
                   {:base-dir (str (System/getProperty "java.io.tmpdir") "/maria-base3")
                    :data-dir (str (System/getProperty "java.io.tmpdir") "/maria-data3")}))

(deftest test-overriden-db-spec-values
  (mariadb/with-db (fn []
                     (is (= {:jdbcUrl "jdbc:mariadb://localhost:5306/dbname1"}
                            mariadb/*db-spec*)))

                   {
                    :port     5306
                    :dbname   "dbname1"
                    :username "username1"
                    :password "password1"
                    :base-dir (str (System/getProperty "java.io.tmpdir") "/maria-base3")
                    :data-dir (str (System/getProperty "java.io.tmpdir") "/maria-data3")}))

(deftest ensure-that-db-is-created
  (mariadb/with-db (fn []
                     (is (= #{#:SCHEMATA{:Database "information_schema"}
                              #:SCHEMATA{:Database "mysql"}
                              #:SCHEMATA{:Database "performance_schema"}
                              #:SCHEMATA{:Database "sys"}
                              #:SCHEMATA{:Database "testdb"}}
                            (into #{}
                                  (jdbc/execute! (jdbc/get-datasource {
                                                                       :dbtype   "mariadb"
                                                                       :host     "localhost"
                                                                       :password "password1"
                                                                       :port     4306
                                                                       :username "username1"})
                                                 ["SHOW DATABASES;"])))))

                   {:dbname "testdb"}))
