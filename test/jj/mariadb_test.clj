(ns jj.mariadb-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [jj.mariadb :as mariadb]
    [mock-clj.core :as mock]
    [next.jdbc :as jdbc])
  (:import (ch.vorburger.mariadb4j DB)
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

(def query ["SELECT schema_name FROM information_schema.SCHEMATA;"])

(defn assert-databases
  ([]
   (jdbc/execute! (jdbc/get-datasource db-spec) ["CREATE DATABASE my_database;"])
   (is (= 5 (count (into #{}
                            (jdbc/execute! (jdbc/get-datasource db-spec) query)))))))

(deftest test-maria-db-init-mariadb
  (let [mariadb (mariadb/init-db! {:port mariadb-port})]
    (is (instance? DB mariadb))
    (mariadb/halt-db!)))

(deftest test-with-mariadb
  (mariadb/with-db! assert-databases {:port mariadb-port}))

(deftest test-maria-db-does-stops-gracefully-when-exception-thrown
  (try
    (mariadb/with-db! (fn [] (throw (Exception. "This is an exception.")))
                      {:port     mariadb-port})
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
                  (is (= (mock/call-count mock-fn) 1) "on-error function should be called"))
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