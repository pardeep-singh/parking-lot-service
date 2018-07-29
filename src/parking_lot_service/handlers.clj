(ns parking-lot-service.handlers
  (:require [parking-lot-service.slots :as ps]))


(defn get-slots
  [fdb-conn zmap]
  (ps/get-slots fdb-conn zmap))
