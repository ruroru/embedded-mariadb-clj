(defproject org.clojars.jj/embedded-mariadb-clj "1.0.1-SNAPSHOT"
  :description "Embedded maria db for clojure"
  :url "https://github.com/ruroru/mariadb-embedded-clj"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[ch.vorburger.mariaDB4j/mariaDB4j "3.1.0"]
                 [org.clojure/clojure "1.12.0"]
                 [org.clojure/tools.logging "1.3.0"]]
  :deploy-repositories [["clojars" {:url      "https://repo.clojars.org"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass}]]
  :profiles {:test {:global-vars    {*warn-on-reflection* true}
                    :resource-paths ["test/resources"]
                    :dependencies   [[babashka/fs "0.5.22"]
                                     [ch.qos.logback/logback-classic "1.5.12"]
                                     [com.github.seancorfield/next.jdbc "1.3.955"]
                                     [mock-clj "0.2.1"]
                                     [org.mariadb.jdbc/mariadb-java-client "3.5.0"]]}}
  :plugins [[lein-ancient "0.7.0"]
            [org.clojars.jj/bump "1.0.0"]]

  :repl-options {:init-ns mariadb-embedded-clj.core})
