(ns main
  (:require [clj-wamp.node :as w]
            [clj-wamp.libs.channels :refer [message-chan error-chan]]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout sub]]))

(defn connected
  [_]
  "Connected")

(defn authenticate
  [& args]
  (println "Authenticate " args)
  true
  )

(def connector (w/create {:router-uri  "ws://127.0.0.1:8280" :realm "tour" :debug? true
                          :reg-on-call {"com.manager.connected"
                                        connected
                                        }

                          :sub-on-call [["wamp.session.on_join" (chan 1)]
                                        ["wamp.session.on_leave" (chan 1)]
                                        ]
                          :reconnect?  false
                          :authenticate? true
                          :auth-details {
                                         :authid "authenticator1"
                                         :authmethods ["wampcra"]
                                         :secret "secret123"
                                         }}))

(def connection (w/connect! connector))


(defn add
  [x y]
  (+ x y)
  )

(def subscriptions @(:subscriptions connection))
(def unreg-subs (:unregistered subscriptions))
(def pending-subs (:pending subscriptions))

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
