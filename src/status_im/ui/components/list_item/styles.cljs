(ns status-im.ui.components.list-item.styles
  (:require [status-im.ui.components.colors :as colors]))

(defn container [small?]
  {:height           (if small? 52 64)
   :align-items      :center
   :flex-direction   :row
   :background-color :white})

(defn title [small? subtitle]
  (merge (when-not small?
           {:typography :title})
         (when subtitle
           {:typography :main-medium})))

(def subtitle
  {:margin-top  4
   :color       colors/gray})

(def accessory-text
  {:color        colors/gray})

(defn radius [size] (/ size 2))

(defn photo [size]
  {:border-radius (radius size)
   :width         size
   :height        size})

(def error
  {:bottom-value 0
   :color        colors/red-light
   :font-size    12})
