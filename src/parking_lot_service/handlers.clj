(ns parking-lot-service.handlers
  (:require [parking-lot-service.slots :as ps]))


(defn get-slots
  [fdb-conn zmap]
  (ps/get-slots fdb-conn zmap))


(defn get-slot
  [fdb-conn slot-id]
  (ps/get-slot fdb-conn slot-id))


(defn park-vehicle
  [fdb params]
  (ps/park-vehicle fdb params))


(defn unpark-vehicle
  [fdb params]
  (ps/unpark-vehicle fdb params))
