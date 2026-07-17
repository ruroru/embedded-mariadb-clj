## Installation

```clojure
[org.clojars.jj/embedded-mariadb-clj "1.2.1"]
```

## Usage

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.jj/embedded-mariadb-clj.svg)](https://clojars.org/org.clojars.jj/embedded-mariadb-clj)

``` clojure
(:require
    [embedded.mariadb :as mariadb]
    [next.jdbc :as jdbc])

;; with-db passes a db-spec map ({:jdbcUrl ...}) to your function
(mariadb/with-db (fn [db-spec]
                   (jdbc/execute!
                     (jdbc/get-datasource db-spec)
                     ["CREATE DATABASE my_db;"]))
                 {:port     4306
                  :on-error (fn [ex]
                              (println (type ex)))})

;; or manage the lifecycle manually
(def db (mariadb/init-db {:port 4306}))
(mariadb/halt-db db)
```

### init-db configuration

| Type    | key                   | description                                              | default value   |
|---------|-----------------------|----------------------------------------------------------|-----------------|
| int     | port                  | Maria db port                                            | 4306            |    
| Boolean | delete-after-shutdown | Delete maria db files after shutdown                     | true            |
| String  | base-dir              | Path to location where mariadb executable will be stored | /tmp/maria-base |
| File    | base-dir              | Path to location where mariadb executable will be stored | /tmp/maria-base |
| String  | data-dir              | Path, where data will be stored                          | /tmp/maria-data |
| File    | data-dir              | Path, where data will be stored                          | /tmp/maria-data |
| Boolean | security-disabled     | Skip grant tables                                        | true            |

### with-db! configuration

with-db contains additional params

| Type   | key       | description                                                                    | default value |
|--------|-----------|--------------------------------------------------------------------------------|---------------|
| IFn    | on-error  | Function to call, if exception is caught                                       | nil           |
| String | dbname    | creates database if provided                                                   | "testdb"      |

## Running in Docker

MariaDB can also be started in a separate Docker container instead of the embedded process, with the port published on
localhost, so tests connect to it exactly like to the embedded database. Pass `:containerized true` to `with-db` or
`init-db` — the official [mariadb](https://hub.docker.com/_/mariadb) image is used and pulled automatically on first
use (no Dockerfile is required), and the container is started before your function runs and stopped afterwards:

```clojure
(mariadb/with-db assert-databases {:port          4306
                                   :containerized true})
```

The only requirement is a working `docker` CLI. The image is only downloaded the first time; after that it is cached
by Docker and the container starts in a few seconds.

Containerized specific configuration (applies to both `init-db` and `with-db`):

| Type    | key             | description                                            | default value    |
|---------|-----------------|--------------------------------------------------------|------------------|
| Boolean | containerized   | Start MariaDB in a Docker container                    | false            |
| String  | docker-image    | Docker image to run, pulled if it is missing           | "mariadb:latest" |
| long    | startup-timeout | Milliseconds to wait for the container to be ready     | 120000           |
| Boolean | in-memory       | Keep the data dir in RAM (tmpfs), never touching disk  | false            |

The container never leaves data behind — it is removed on shutdown either way. `:in-memory true` additionally mounts
the data directory on tmpfs, so nothing is written to the drive even while the database is running, and writes are
faster.

`port` and `dbname` are honored like in embedded mode; `base-dir`, `data-dir`, `delete-after-shutdown` and
`security-disabled` are ignored. Unlike embedded mode, the container enforces credentials: connect as user `root` with
an empty password (the db-spec passed to your function already includes it).

There is no global state — each `with-db` call passes its own db-spec to your function, so multiple containers can run
at the same time on different ports, e.g. with several `init-db` or nested `with-db` calls.

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
