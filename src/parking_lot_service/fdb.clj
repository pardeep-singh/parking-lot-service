(ns parking-lot-service.fdb
  (:require [clj-fdb.FDB :as fdb]))


(defn open-conn
  ([api-version]
   (-> api-version
       fdb/select-api-version
       fdb/open))
  ([api-version cluster-file]
   (-> api-version
       fdb/select-api-version
       (fdb/open cluster-file))))
