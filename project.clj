(defproject parking-lot-service "0.1.0"
  :description "RESTful API service for managing Parking lot."
  :url "http://example.com/FIXME"
  :license {:name "Proprietary"}
  :uberjar-exclusions [#"(?i)^META-INF/[^/]*\.(SF|RSA)$"]
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [compojure "1.5.1"]
                 [ring/ring-defaults "0.2.1"]
                 [ring/ring-jetty-adapter "1.2.0"]
                 [cheshire "5.7.0"]
                 [com.stuartsierra/component "0.2.3"]
                 [org.clojure/tools.logging "0.4.0"]
                 [clj-http "3.7.0"]
                 [clj-fdb "0.1.0"]]
  :source-paths ["src"]
  :global-vars {*warn-on-reflection* true}
  :manifest {"Project-Name" ~#(:name %)
             "Project-Version" ~#(:version %)
             "Build-Date" ~(str (java.util.Date.))}
  :profiles {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                                  [ring/ring-mock "0.3.0"]]}}
  :aot [parking-lot-service.core]
  :main parking-lot-service.core)
