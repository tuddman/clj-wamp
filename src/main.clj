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

(def connector (w/create {:router-uri "ws://127.0.0.1:8180/ws" :realm "tour" :debug? true
                          :on-call    {"com.manager.connected"
                                       connected
                                       }
                          :reconnect? false}))

(def connection (w/connect! connector))


(defn add
  [x y]
  (+ x y)
  )

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
