(ns parking-lot-service.components
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [com.stuartsierra.component :as csc]
            [clojure.tools.logging :as ctl]
            [parking-lot-service.fdb :as pfdb]))


;; Component to setup the APP routes
(defrecord Routes [app routes fdb]
  csc/Lifecycle

  (start [this]
    (if (nil? routes)
      (do
        (ctl/info "Setting up routes component")
        (assoc this
               :routes (app fdb)))
      (do
        (ctl/info "Routes component already started")
        this)))

  (stop [this]
    (if routes
      (do
        (ctl/info "Removing routes component")
        (assoc this
               :routes nil))
      (do
        (ctl/info "Routes component is nil")
        this))))


;; Component to manage the Jetty Server Lifecycle
(defrecord HttpServer [port routes http-server]
  csc/Lifecycle

  (start [this]
    (if (nil? http-server)
      (let [server (run-jetty (:routes routes)
                              {:port port
                               :join? false})]
        (ctl/info "Starting HTTP server")
        (assoc this
               :http-server server))
      (do
        (ctl/info "HTTP server already started")
        this)))

  (stop [this]
    (if http-server
      (do
        (ctl/info "Stopping HTTP server")
        (.stop ^org.eclipse.jetty.server.Server (:http-server this))
        (assoc this
               :http-server nil))
      (do
        (ctl/info "HTTP server component is nil")
        this))))


(defrecord FDB [fdb-conn api-version cluster-file-path]
  csc/Lifecycle

  (start [this]
    (if (nil? fdb-conn)
      (do
        (ctl/info "Starting FDB Component")
        (let [conn (if (seq cluster-file-path)
                     (pfdb/open-conn api-version cluster-file-path)
                     (pfdb/open-conn api-version))]
          (assoc this
                 :fdb-conn conn)))
      (do
        (ctl/info "FDB connection already Exists")
        this)))

  (stop [this]
    (if fdb-conn
      (do
        (ctl/info "Stopping FDB Component")
        (assoc this
               :fdb-conn nil))
      (do
        (ctl/info "Can't stop FDB Component as it is already nil.")
        this))))
