(ns parking-lot-service.http-util
  (:require [cheshire.core :as cc]))


(defn json-response
  [response & {:keys [status]}]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (when response
           (cc/generate-string response))})


(defn ok
  [zmap]
  (json-response zmap
                 :status 200))


(defn internal-server-error
  [message]
  (json-response {:error message}
                 :status 500))
