(ns parking-lot-service.core
  (:gen-class)
  (:require [clojure.tools.logging :as ctl]
            [compojure.core :as cc :refer [context defroutes POST GET PUT DELETE]]
            [compojure.route :as route]
            [com.stuartsierra.component :as csc]
            [parking-lot-service.components :as pc]
            [parking-lot-service.http-util :as phu]
            [parking-lot-service.middleware :as pm]))


(defonce ^{:doc "Server system representing HTTP server."}
  server-system nil)


(defn app-routes
  "Returns the APP routes and injects the dependency required by routes."
  []
  (cc/routes
   
   (GET "/ping" [] (phu/ok {:ping "PONG"}))

   (GET "/hello" [] (phu/ok {:message "Hello World from Clojure."}))

   (route/not-found "Not Found")))


(defn app
  "Constructs routes wrapped by middlewares."
  []
  (-> (app-routes)
      pm/wrap-exceptions
      pm/log-requests))


(defn start-system
  "Starts the system given system-map."
  [system]
  (let [server-system* (csc/start system)]
    (alter-var-root #'server-system (constantly server-system*))))


(defn stop-system
  "Stops the system given a system-map."
  [system]
  (let [server-system* (csc/stop system)]
    (alter-var-root #'server-system (constantly server-system*))))


(defn construct-system
  [configs]
  (let [routes-comp (pc/map->Routes {:app app})
        http-server-comp (pc/map->HttpServer {:port (:port configs)})]
    (csc/system-map
     :routes routes-comp
     :http-server (csc/using http-server-comp
                             [:routes]))))


(defn -main
  [& args]
  (try
    (let [configs {:port 9099}
          system (construct-system configs)]
      (start-system system)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (ctl/info "Running Shutdown Hook")
                                   (stop-system server-system)
                                   (shutdown-agents)
                                   (ctl/info "Done with shutdown hook")))))
    (catch Exception exception
      (ctl/error exception
                 "Exception while starting the application"))))
