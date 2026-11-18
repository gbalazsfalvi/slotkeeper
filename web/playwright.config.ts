import { defineConfig } from "@playwright/test";

const baseURL = process.env.SLOTKEEPER_BASE_URL ?? "http://127.0.0.1:4190";
const target = new URL(baseURL);
if (
  target.protocol !== "http:" ||
  target.hostname !== "127.0.0.1" ||
  target.pathname !== "/" ||
  target.username ||
  target.password ||
  target.search ||
  target.hash
) {
  throw new Error(
    "Browser verification accepts only an owned IPv4 loopback Slotkeeper origin.",
  );
}

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  outputDir: "../.local/browser/test-results",
  reporter: [
    ["list"],
    ["html", { outputFolder: "../.local/browser/report", open: "never" }],
  ],
  use: {
    baseURL,
    browserName: "chromium",
    colorScheme: "light",
    reducedMotion: "reduce",
    trace: "off",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [
    { name: "desktop", use: { viewport: { width: 1440, height: 1050 } } },
    {
      name: "mobile",
      use: {
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true,
        deviceScaleFactor: 1,
      },
    },
  ],
});
