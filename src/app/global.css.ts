// src/shared/styles/global.css.ts
import { globalStyle } from "@vanilla-extract/css";

/* =========================
  1. RESET (브라우저 초기화)
========================= */

globalStyle("*, *::before, *::after", {
  boxSizing: "border-box",
});

globalStyle("html, body", {
  margin: 0,
  padding: 0,
  width: "100%",
  height: "100%",
});

globalStyle("body", {
  fontFamily: `system-ui, -apple-system, Segoe UI, Roboto, sans-serif`,
  WebkitFontSmoothing: "antialiased",
  MozOsxFontSmoothing: "grayscale",
  backgroundColor: "white",
  color: "black",
  lineHeight: 1.5,
  // 배경 이미지 설정 시
  // backgroundImage: "url('/background.jpeg')",
  // backgroundSize: "cover",
});

globalStyle("#root", {
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  width: "100%",
  height: "100%",
});

/* =========================
  2. 기본 요소 초기화
========================= */

globalStyle("h1, h2, h3, h4, h5, h6", {
  margin: 0,
  fontWeight: 600,
});

globalStyle("p", {
  margin: 0,
});

globalStyle("ul, ol", {
  margin: 0,
  padding: 0,
  listStyle: "none",
});

globalStyle("a", {
  color: "inherit",
  textDecoration: "none",
});

globalStyle("button", {
  all: "unset",
  cursor: "pointer",
});

globalStyle("input, textarea, select", {
  font: "inherit",
  color: "inherit",
});

globalStyle("img", {
  maxWidth: "100%",
  display: "block",
});

/* =========================
  3. 스크롤바 (선택)
========================= */

globalStyle("::-webkit-scrollbar", {
  width: "8px",
  height: "8px",
});

globalStyle("::-webkit-scrollbar-thumb", {
  background: "#2a2a2a",
  borderRadius: "8px",
});

globalStyle("::-webkit-scrollbar-track", {
  background: "transparent",
});

globalStyle("::-webkit-scrollbar-corner", {
  background: "transparent",
});

/* =========================
  4. 선택 영역 (UX 개선)
========================= */

globalStyle("::selection", {
  background: "#4f46e5",
  color: "white",
});
