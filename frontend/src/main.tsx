import { createRoot } from "react-dom/client";
import { App } from "./app/App";
import "./styles.css";

createRoot(document.getElementById("root") as HTMLElement).render(<App />);
