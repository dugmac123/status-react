(ns status-im.react-native.js-dependencies
  (:require-macros [status-im.utils.js-require :as js-require]))

(def config                 (js-require/js-require "react-native-config"))
(def fs                     (js-require/js-require "react-native-fs"))
(def http-bridge            (js-require/js-require "react-native-http-bridge"))
(def keychain               (js-require/js-require "react-native-keychain"))
(def qr-code                (js-require/js-require "react-native-qrcode"))
(def react-native           (js/require "react-native"))
(def webview-bridge         (js/require "react-native-webview-bridge"))
(def webview                #js {:WebView #js {}})
(def EventEmmiter           (fn [] #js {}))
(def securerandom           (js-require/js-require "react-native-securerandom"))
(defn secure-random []      (.-generateSecureRandom (securerandom)))
(def fetch-polyfill         (js-require/js-require "react-native-fetch-polyfill"))
(defn fetch []              (.-default (fetch-polyfill)))
(def i18n                   (js/require "i18n-js"))
(def react-native-languages (.-default (js/require "react-native-languages")))
(def desktop-linking        (.-DesktopLinking (.-NativeModules react-native)))
(def desktop-menu           (js/require "react-native-desktop-menu"))
(def desktop-config         (js/require "react-native-desktop-config"))
(def desktop-shortcuts      (js/require "react-native-desktop-shortcuts"))
(def react-native-firebase  (fn [] #js {}))
(def touchid                (fn [] #js {}))
(def camera                 (fn [] #js {:default #js {:constants {:Aspect "Portrait"}}}))
(def status-keycard         (fn [] #js {:default #js {}}))
(def dialogs                (fn [] #js {}))
(def dismiss-keyboard       (fn [] #js {}))
(def image-crop-picker      (fn [] #js {}))
(def image-resizer          (fn [] #js {}))
(def svg                    #js {})
(def snoopy                 (fn [] #js {}))
(def snoopy-filter          (fn [] #js {}))
(def snoopy-bars            (fn [] #js {}))
(def snoopy-buffer          (fn [] #js {}))
(def background-timer       (fn [] #js {:setTimeout (fn [cb ms] (js/setTimeout cb ms))}))
(def react-navigation       (js/require "react-navigation"))
(def react-native-navigation-twopane  (js/require "react-native-navigation-twopane"))
(def react-native-shake     (fn [] #js {}))
(def react-native-mail      (fn [] #js {:mail (fn [])}))
