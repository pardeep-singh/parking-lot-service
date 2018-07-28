(ns parking-lot-service.slots
  (:require [clojure.string :as cs]))


(defonce slot-types
  {0 "2 Wheeler"
   1 "4 Wheeler"})

(defonce slot-status
  {0 "Available"
   1 "Not Available"})


(defn get-slots
  [query]
  (let [slots (for [x ["a" "b" "c" "d"]
                    y ["a" "b" "c" "d"]]
                {:id (cs/join [x y])
                 :type (get slot-types (rand-int 2))
                 :status (get slot-status (rand-int 2))})]
    {:slots slots}))
