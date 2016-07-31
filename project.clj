(defproject clj-wamp "2.1.0"
  :description "The WebSocket Application Messaging Protocol for Clojure"
  :url "https://github.com/cgmartin/clj-wamp"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/core.incubator "0.1.3"]
                 [org.clojure/tools.logging "0.2.6"]
                 [com.taoensso/timbre "4.1.4"]
                 [org.clojure/data.codec "0.1.0"]
                 [http-kit "2.1.18"]
                 [cheshire "5.5.0"]
                 [stylefruits/gniazdo "0.4.1"]
                 [pandect "0.5.4"]]
  :profiles {:1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :dev {:dependencies [[log4j "1.2.17" :exclusions [javax.mail/mail
                                                               javax.jms/jms
                                                               com.sun.jdmk/jmxtools
                                                               com.sun.jmx/jmxri]]]}})
