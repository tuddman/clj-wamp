(ns main
  (:require [clj-wamp.node :as w]
            [clj-wamp.client.v2 :refer [error]]
            [clj-wamp.libs.channels :refer [message-chan error-chan]]
            [clj-wamp.info.uris :refer [error-uri]]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout sub]]))

(def connection (atom nil))
(defn connected
  [_]
  "Connected")

(def db
  {"a2aru46mlmle5vp1jt3dr49j86" {
                                :secret "secret2"
                                :role :frontend
                                }
   "a2aru46mlmle5vp1jt3dr49j87" {
                                 :secret "prq7+YkJ1/KlW1X0YczMHw=="
                                 :role :frontend
                                 :salt "salt123"
                                 :iterations 100
                                 :keylen 16
                                 }})

(defn authenticate
  [args]
  (println "Authenticate " args)
  (let [[realm authid details] (:seq-args args)]
    (println "authenticate called:" realm authid details)
    (if-let [user (get db authid)]
      user
      {:error {:uri (error-uri :authorization-failed)
               :message "No Active session found"}
       }
      )
    )
  )

(def connector (w/create {:router-uri  "ws://127.0.0.1:8280" :realm "tour" :debug? true
                          :reg-on-call {"com.tourviewer.authenticate"
                                        authenticate
                                        }
                          ;
                          ;:sub-on-call [["wamp.session.on_join" (chan 1)]
                          ;              ["wamp.session.on_leave" (chan 1)]
                          ;              ]
                          :reconnect?  false
                          :authenticate? true
                          :auth-details {
                                         :authid "authenticator1"
                                         :authmethods ["wampcra"]
                                         :secret "secret123"
                                         }}))

(w/connect! (reset! connection connector)   )
;
;
;(defn add
;  [x y]
;  (+ x y)
;  )
;
;(def subscriptions @(:subscriptions @connection))
;(def unreg-subs (:unregistered subscriptions))
;(def pending-subs (:pending subscriptions))

;(dotimes [n 10] (w/register! connector (str "com.tourviewer.test" n) add))


;
;
;(def subscriber-one (chan))
;(def subscriber-two (chan))
;
;(defn take-and-print [channel prefix]
;  (go-loop []
;    (println prefix ": " (<! channel))
;    (recur)))
;
;
;(sub message-chan :RESULT subscriber-one)
;(sub error-chan :CALL subscriber-two)
;
;
;(take-and-print subscriber-one "subscriber-one")
;(take-and-print subscriber-two "subscriber-two")
