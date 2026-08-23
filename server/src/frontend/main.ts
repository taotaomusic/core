import { createApp } from "vue";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";

const app = createApp({ template: `<div>Hello</div>` });
app.use(ElementPlus);
app.mount("#app");