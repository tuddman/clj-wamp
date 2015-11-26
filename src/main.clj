(ns main
  (:require [clj-wamp.node :as w]
            [clj-wamp.libs.channels :refer [message-chan error-chan]]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout sub]]))

(def connector (w/create {:router-uri "ws://127.0.0.1:8180/ws" :realm "tour" :debug? true}))

(def connection (w/connect! connector))

(defn add
  [x y]
  (+ x y)
  )

(w/register! connector "com.tourviewer.test" add)



(def subscriber-one (chan))
(def subscriber-two (chan))

(defn take-and-print [channel prefix]
  (go-loop []
    (println prefix ": " (<! channel))
    (recur)))


(sub message-chan :RESULT subscriber-one)
(sub error-chan :CALL subscriber-two)


(take-and-print subscriber-one "subscriber-one")
(take-and-print subscriber-two "subscriber-two")
