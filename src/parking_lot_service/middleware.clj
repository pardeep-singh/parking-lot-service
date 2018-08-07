(ns parking-lot-service.middleware
  (:require [parking-lot-service.http-util :as phu]
            [clojure.tools.logging :as ctl]))


(defn wrap-exceptions
  [handler]
  (fn [req]
    (try
      (handler req)
      (catch java.lang.AssertionError exception
        (ctl/error exception)
        (phu/bad-request "Invalid data"))
      (catch Exception exception
        (ctl/error exception)
        (if (= (:type (ex-data exception))
               :not-found)
          (phu/not-found (:msg (ex-data exception)))
          (phu/internal-server-error "Internal Server Error"))))))


(defn log-requests
  [handler]
  (fn [req]
    (let [timestamp (System/currentTimeMillis)
          response (handler req)]
      (ctl/info {:status (:status response)
                 :method (:request-method req)
                 :uri (:uri req)
                 :duration (str (- (System/currentTimeMillis)
                                   timestamp)
                                "ms")})
      response)))
