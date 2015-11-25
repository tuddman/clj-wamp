(ns clj-wamp.channels
  (:require
    [clojure.core.async :refer [chan pub]]))

(def messages (chan))
(def errors (chan))

(def message-chan
  (pub messages #(:topic %)))

(def error-chan
  (pub errors #(:topic %)))