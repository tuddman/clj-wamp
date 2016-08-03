(ns clj-wamp.info.ids)

(def ^:const message-id-table
  {
    :HELLO 1
    :WELCOME 2
    :ABORT 3
    :CHALLENGE 4
    :AUTHENTICATE 5
    :GOODBYE 6
    :ERROR 8
    :PUBLISH 16
    :PUBLISHED 17
    :SUBSCRIBE 32
    :SUBSCRIBED 33
    :UNSUBSCRIBE 34
    :UNSUBSCRIBED 35
    :EVENT 36
    :CALL 48
    :CANCEL 49
    :RESULT 50
    :REGISTER 64
    :REGISTERED 65
    :UNREGISTER 66
    :UNREGISTERED 67
    :INVOCATION 68
    :INTERRUPT 69
    :YIELD 70 })

(defmacro message-id
  [msg-keyword]
  (get message-id-table msg-keyword))

(defn- invert-map
  [dict]
  (into {} (map (fn [[k v]] [v k]) dict)))

(def ^:const reverse-message-id-table (invert-map message-id-table))

(defn reverse-message-id [msg-num]
  (get reverse-message-id-table msg-num))

