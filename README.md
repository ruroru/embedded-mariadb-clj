## Usage
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.jj/embedded-mariadb-clj.svg)](https://clojars.org/org.clojars.jj/embedded-mariadb-clj)


``` clojure
(:require
    [embedded.mariadb :as mariadb]
    [next.jdbc :as jdbc])

(def data-source (jdbc/get-datasource
                     {:dbtype "mariadb"
                      :host   "localhost"
                      :port   4306}))

(mariadb/with-db! (fn[] 
                    (jdbc/execute! 
                        (jdbc/get-datasource db-spec) 
                        ["CREATE DATABASE my_db;"]))
        {:port   4306
         :on-error (fn [ex]
                       (println (type ex)})
                       
(mariadb/init-db {:port   4306})         

(mariadb/halt-db!)

``` 

### with-db! configuration

| key                   | description                                              | default value   |
|-----------------------|----------------------------------------------------------|-----------------|
| port                  | Maria db port                                            | 4306            |    
| delete-after-shutdown | Delete maria db files after shutdown                     | true            |
| base-dir              | Path to location where mariadb executable will be stored | /tmp/maria-base |
| data-dir              | Path, where data will be stored                          | /tmp/maria-data |
| security-disabled     | Skip grant tables                                        | true            |
| on-error              | Function to call, if exception is caught                 | nil             |


### init-db configuration

| key                   | description                                              | default value   |
|-----------------------|----------------------------------------------------------|-----------------|
| port                  | Maria db port                                            | 4306            |    
| delete-after-shutdown | Delete maria db files after shutdown                     | true            |
| base-dir              | Path to location where mariadb executable will be stored | /tmp/maria-base |
| data-dir              | Path, where data will be stored                          | /tmp/maria-data |
| security-disabled     | Skip grant tables                                        | true            |


## License

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
