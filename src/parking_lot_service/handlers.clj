(ns parking-lot-service.handlers
  (:require [parking-lot-service.slots :as ps]))



(defn get-slots
  [zmap]
  (ps/get-slots zmap))
