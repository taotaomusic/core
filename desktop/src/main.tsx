import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./App";
// 组件库适配层样式全局引入一次：ui-* 原语的视觉规格集中在这里维护
import "./ui/radix.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
