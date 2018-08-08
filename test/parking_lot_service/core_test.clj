(ns parking-lot-service.core-test
  (:require [parking-lot-service.core :as pc]
            [clojure.test :refer :all]
            [parking-lot-service.slots :as ps]
            [clj-fdb.subspace.subspace :as fsubspace]
            [clj-fdb.tuple.tuple :as ftuple]
            [clj-http.client :as http]
            [cheshire.core :as cc]))


(def service-port (atom nil))
(def server-system (atom nil))
(def service-url (atom nil))
(def characters (vec "abcdefghijklmnopqrstuvwxyz"))


(defn test-setup
  []
  (let [random-service-port (+ 9099
                               (rand-int 1000))
        system (-> {:port random-service-port}
                   pc/construct-system
                   pc/start-system)]
    (reset! service-port random-service-port)
    (reset! server-system system)
    (reset! service-url (str "http://localhost:" @service-port))))


(defn rand-string
  [n]
  (apply str (take n (repeatedly #(rand-nth characters)))))


(defn test-cleanup
  []
  (pc/stop-system @server-system)
  (reset! server-system nil))


(defn once-fixture
  [tests]
  (test-setup)
  (tests)
  (test-cleanup))


(defn each-fixture
  [tests]
  (let [random-str (rand-string 10)
        slots-subspace (str "slots:" random-str)
        slots-info-subspace (str "slots_info:" random-str)]
    (binding [ps/parking-lot-subspace (fsubspace/create-subspace (ftuple/from slots-subspace))
              ps/slots-info-subspace (fsubspace/create-subspace (ftuple/from slots-info-subspace))]
      (ps/init-parking-lot (:fdb @server-system)
                           ps/rows
                           ps/columns
                           ps/motorcycle-slots
                           ps/compact-slots
                           ps/large-slots)
      (tests)
      (ps/clear-parking-lot (:fdb @server-system)))))


(use-fixtures :once once-fixture)
(use-fixtures :each each-fixture)


(deftest get-slots-api-test
  (testing "GET /slots API"
    (let [response (-> (str @service-url "/slots")
                       http/get
                       :body
                       (cc/parse-string true))]
      (is (= (count (get-in response [:slots :motorcycle_available_slots])) 5)
          "Motorcycle available slots counts is 5.")
      (is (= (count (get-in response [:slots :compact_available_slots])) 5)
          "Compact available slots counts is 5.")
      (is (= (count (get-in response [:slots :large_available_slots])) 15)
          "Large available slots counts is 15.")))

  (testing "GET /slots/:id API"
    (let [response (-> (str @service-url "/slots/AA")
                       http/get
                       :body
                       (cc/parse-string true))]
      (is (= (:slot response)
             {:type "motorcycle" :status "available" :id "AA"})
          "Response contains expected fields with expected values."))))
