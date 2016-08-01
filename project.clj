(defproject tuddman/clj-wamp "2.1.0-SNAPSHOT"
  :description "The WebSocket Application Messaging Protocol for Clojure"
  :url "https://github.com/tuddman/clj-wamp"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.5.3"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.385"]
                 [org.clojure/core.incubator "0.1.4"]
                 [org.clojure/data.codec "0.1.0"]
                 [com.taoensso/timbre "4.7.3"]
                 [cheshire "5.6.3"]
                 [http-kit "2.2.0"]  
                 [pandect "0.6.0"]
                 [stylefruits/gniazdo "1.0.0"]]
  :profiles {:1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :dev {:dependencies [[log4j "1.2.17" :exclusions [javax.mail/mail
                                                               javax.jms/jms
                                                               com.sun.jdmk/jmxtools
                                                               com.sun.jmx/jmxri]]]}})
