(ns embedded.mariadb-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [embedded.mariadb :as mariadb]
    [mock-clj.core :as mock]
    [next.jdbc :as jdbc])
  (:import (ch.vorburger.mariadb4j DB)
           (java.io File)
           (java.sql SQLIntegrityConstraintViolationException)))

(defn mock-fn [])
(def mariadb-port 4308)
(def db-spec {:dbtype "mariadb"
              :host   "localhost"
              :port   mariadb-port})

(use-fixtures :each
              (fn [f]
                (f)
                (when (mariadb/is-running?)
                  (mariadb/halt-db!))))

(def ^:private query ["SELECT schema_name FROM information_schema.SCHEMATA;"])
(def ^:private db-name "my_database")

(defn assert-databases []
  (jdbc/execute! (jdbc/get-datasource db-spec) [(format "CREATE DATABASE %s;" db-name)])
  (is (contains? (into #{}
                       (jdbc/execute! (jdbc/get-datasource db-spec) query))
                 #:SCHEMATA{:schema_name db-name})))

(deftest test-maria-db-init-mariadb-with-default-base-and-data-dirs
  (let [mariadb (mariadb/init-db! {:port mariadb-port})]
    (is (instance? DB mariadb))
    (mariadb/halt-db!)))


(deftest test-maria-db-init-mariadb-can-use-file-for-base-and-data-dirs
  (let [mariadb (mariadb/init-db! {:port     mariadb-port
                                   :base-dir (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-data2"))
                                   :data-dir (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-base2"))})]
    (is (instance? DB mariadb))
    (mariadb/halt-db!)))


(deftest test-maria-db-init-mariadb-can-use-string-for-base-and-data-dirs
  (let [mariadb (mariadb/init-db! {:port     mariadb-port
                                   :base-dir (str (System/getProperty "java.io.tmpdir") "/maria-base3")
                                   :data-dir (str (System/getProperty "java.io.tmpdir") "/maria-data3")})]
    (is (instance? DB mariadb))
    (mariadb/halt-db!)))


(deftest test-with-mariadb-default-base-and-data-dirs
  (mariadb/with-db! assert-databases {:port mariadb-port}))


(deftest test-with-mariadb-can-use-file-for-base-and-data-dirs
  (mariadb/with-db! assert-databases {:port     mariadb-port
                                      :base-dir (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-data2"))
                                      :data-dir (File. ^String (str (System/getProperty "java.io.tmpdir") "/maria-base2"))}))


(deftest test-with-mariadb-can-use-string-for-base-and-data-dirs
  (mariadb/with-db! assert-databases {:port     mariadb-port
                                      :base-dir (str (System/getProperty "java.io.tmpdir") "/maria-base3")
                                      :data-dir (str (System/getProperty "java.io.tmpdir") "/maria-data3")}))


(deftest test-maria-db-does-stops-gracefully-when-exception-thrown
  (try
    (mariadb/with-db! (fn [] (throw (Exception. "This is an exception.")))
                      {:port mariadb-port})
    (is (= false (mariadb/is-running?)) "Mariadb should be running")
    (catch Exception _
      (is (= false (mariadb/is-running?)) "Mariadb should not be running"))))


(deftest test-maria-db-does-ignores-exception
  (mock/with-mock [mock-fn 1]
                  (try
                    (mariadb/with-db! (fn [] (throw (SQLIntegrityConstraintViolationException. "This is an exception.")))
                                      {:port     mariadb-port
                                       :on-error mock-fn})
                    (catch Exception _))
                  (is (= false (mariadb/is-running?)) "Mariadb should not be running")
                  (is (= (mock/call-count mock-fn) 1) "on-error function should be called")
                  (is (instance? SQLIntegrityConstraintViolationException (first (first (mock/calls mock-fn)))) "on-error function should be called"))
  (is (= (mariadb/is-running?) false) "Mariadb should not be running"))

(deftest is-running
  (testing "returns true when maria-db is running"
    (is (not (mariadb/is-running?))))
  (testing "returns false when maria-db is not running"
    (mariadb/init-db! {:port mariadb-port})
    (is (= true (mariadb/is-running?)))
    (assert-databases)
    (mariadb/halt-db!)
    (is (= false (mariadb/is-running?)))))