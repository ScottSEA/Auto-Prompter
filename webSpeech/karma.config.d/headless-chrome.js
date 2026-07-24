// Kotlin's wasmJs browser test task generates a base karma.conf.js and then loads every file in this
// karma.config.d/ directory, so settings here override the defaults. We force a real *headless*
// Chromium with the flags required to launch it in a sandboxed/CI-style environment (no visible UI):
//   --no-sandbox / --disable-setuid-sandbox : Chrome cannot use its sandbox under many CI users.
//   --disable-gpu / --disable-dev-shm-usage : avoid GPU + tiny /dev/shm crashes in headless runs.
// karma-chrome-launcher locates the browser via the CHROME_BIN env var (set by the test command) or
// a standard install path.
config.set({
    customLaunchers: {
        ChromeHeadlessNoSandbox: {
            base: "ChromeHeadless",
            flags: [
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-gpu",
                "--disable-dev-shm-usage",
            ],
        },
    },
    browsers: ["ChromeHeadlessNoSandbox"],
});
