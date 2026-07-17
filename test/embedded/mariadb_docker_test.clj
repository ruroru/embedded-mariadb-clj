(ns embedded.mariadb-docker-test
  (:require
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [embedded.mariadb :as mariadb]
    [next.jdbc :as jdbc]))

(def ^:private mariadb-port 4309)

(def ^:private query ["SELECT schema_name FROM information_schema.SCHEMATA;"])
(def ^:private db-name "my_database")

(defn- db-spec-for [port]
  {:dbtype "mariadb"
   :host   "localhost"
   :port   port
   :user   "root"})

(defn- schema-names [port]
  (into #{}
        (jdbc/execute! (jdbc/get-datasource (db-spec-for port)) query)))

(defn- docker-available? []
  (try
    (zero? (:exit (shell/sh "docker" "version")))
    (catch Exception _ false)))

(defn- assert-databases [db-spec]
  (jdbc/execute! (jdbc/get-datasource db-spec) [(format "CREATE DATABASE %s;" db-name)])
  (is (contains? (into #{}
                       (jdbc/execute! (jdbc/get-datasource db-spec) query))
                 #:SCHEMATA{:schema_name db-name})))

(defn- fail-on-error [e]
  (is (nil? e) "containerized db should not fail"))

(defn- assert-connection-fails [port]
  (try
    (jdbc/execute! (jdbc/get-datasource (db-spec-for port)) query)
    (is false "connection should fail after db is stopped")
    (catch Exception e
      (is (str/includes? (ex-message e) (str "localhost:" port))))))


(deftest test-with-db-containerized
  (if (docker-available?)
    (mariadb/with-db assert-databases {:port          mariadb-port
                                       :containerized true
                                       :on-error      fail-on-error})
    (println "Docker is not available, skipping test-with-db-containerized")))


(deftest test-with-db-containerized-creates-custom-dbname
  (if (docker-available?)
    (mariadb/with-db (fn [db-spec]
                       (is (= {:jdbcUrl (format "jdbc:mariadb://localhost:%s/dbname1?user=root" mariadb-port)}
                              db-spec))
                       (is (contains? (schema-names mariadb-port)
                                      #:SCHEMATA{:schema_name "dbname1"})))
                     {:port          mariadb-port
                      :dbname        "dbname1"
                      :containerized true
                      :on-error      fail-on-error})
    (println "Docker is not available, skipping test-with-db-containerized-creates-custom-dbname")))


(deftest test-with-db-containerized-stops-container-after-run
  (if (docker-available?)
    (do
      (mariadb/with-db (fn [_db-spec]) {:port          mariadb-port
                                        :containerized true
                                        :on-error      fail-on-error})
      (assert-connection-fails mariadb-port))
    (println "Docker is not available, skipping test-with-db-containerized-stops-container-after-run")))


(deftest test-manually-start-and-stop-containerized-db
  (if (docker-available?)
    (let [db (mariadb/init-db {:port          mariadb-port
                               :containerized true})]
      (is (some? db) "containerized db should start")
      (try
        (assert-databases (db-spec-for mariadb-port))
        (finally
          (mariadb/halt-db db)))
      (assert-connection-fails mariadb-port))
    (println "Docker is not available, skipping test-manually-start-and-stop-containerized-db")))


(deftest test-two-containers-running-at-the-same-time
  (if (docker-available?)
    (let [port-1 4311
          port-2 4312
          db-1 (mariadb/init-db {:port port-1 :containerized true})
          db-2 (mariadb/init-db {:port port-2 :containerized true})]
      (try
        (is (some? db-1) "first containerized db should start")
        (is (some? db-2) "second containerized db should start")

        (jdbc/execute! (jdbc/get-datasource (db-spec-for port-1)) ["CREATE DATABASE db_one;"])
        (jdbc/execute! (jdbc/get-datasource (db-spec-for port-2)) ["CREATE DATABASE db_two;"])

        (is (contains? (schema-names port-1) #:SCHEMATA{:schema_name "db_one"}))
        (is (not (contains? (schema-names port-1) #:SCHEMATA{:schema_name "db_two"}))
            "containers should not share data")

        (is (contains? (schema-names port-2) #:SCHEMATA{:schema_name "db_two"}))
        (is (not (contains? (schema-names port-2) #:SCHEMATA{:schema_name "db_one"}))
            "containers should not share data")
        (finally
          (mariadb/halt-db db-1)
          (mariadb/halt-db db-2)))
      (assert-connection-fails port-1)
      (assert-connection-fails port-2))
    (println "Docker is not available, skipping test-two-containers-running-at-the-same-time")))


(deftest test-in-memory-containerized-db
  (if (docker-available?)
    (let [db (mariadb/init-db {:port          mariadb-port
                               :containerized true
                               :in-memory     true})]
      (is (some? db) "in-memory containerized db should start")
      (try
        (let [{:keys [out]} (shell/sh "docker" "exec" (:container-id db) "df" "/var/lib/mysql")]
          (is (str/includes? (str out) "tmpfs") "data dir should be mounted on tmpfs"))
        (assert-databases (db-spec-for mariadb-port))
        (finally
          (mariadb/halt-db db)))
      (assert-connection-fails mariadb-port))
    (println "Docker is not available, skipping test-in-memory-containerized-db")))


(deftest test-container-clock-matches-host-timezone
  (if (docker-available?)
    (let [db (mariadb/init-db {:port          mariadb-port
                               :containerized true})]
      (is (some? db) "containerized db should start")
      (try
        (let [value (:now (first (jdbc/execute! (jdbc/get-datasource (db-spec-for mariadb-port))
                                                ["SELECT NOW() AS now;"])))
              db-now ^java.time.LocalDateTime (if (instance? java.sql.Timestamp value)
                                                (.toLocalDateTime ^java.sql.Timestamp value)
                                                value)
              drift (Math/abs (.between java.time.temporal.ChronoUnit/SECONDS
                                        db-now
                                        (java.time.LocalDateTime/now)))]
          (is (< drift 120)
              "NOW() in the container must match the host wall clock, like the embedded db"))
        (finally
          (mariadb/halt-db db))))
    (println "Docker is not available, skipping test-container-clock-matches-host-timezone")))


(deftest test-on-error-is-called-when-function-throws
  (if (docker-available?)
    (let [captured (atom nil)]
      (mariadb/with-db (fn [_db-spec]
                         (throw (Exception. "boom")))
                       {:port          mariadb-port
                        :containerized true
                        :on-error      (fn [e] (reset! captured e))})
      (is (= "boom" (ex-message @captured)))
      (assert-connection-fails mariadb-port))
    (println "Docker is not available, skipping test-on-error-is-called-when-function-throws")))


(deftest test-on-error-is-called-when-image-cannot-be-started
  (if (docker-available?)
    (let [captured (atom nil)]
      (mariadb/with-db (fn [_db-spec]
                         (is false "function should not run when the container cannot start"))
                       {:port          mariadb-port
                        :containerized true
                        :docker-image  "embedded-mariadb-clj/does-not-exist:0.0.0"
                        :on-error      (fn [e] (reset! captured e))})
      (is (some? @captured) "on-error should receive the startup exception"))
    (println "Docker is not available, skipping test-on-error-is-called-when-image-cannot-be-started")))
