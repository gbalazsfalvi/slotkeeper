import { expect, test } from "@playwright/test";
import type { Page, TestInfo } from "@playwright/test";

// These identities exist only in the owned local synthetic seed (docs/API.md).
const fixturePassword = "Slotkeeper-Local-Only!";

async function signIn(page: Page, username: string) {
  await page.goto("/");
  await page.getByLabel("Username", { exact: true }).fill(username);
  await page.getByLabel("Password", { exact: true }).fill(fixturePassword);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Book a room", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Refresh availability" }),
  ).toBeEnabled();
}

async function chooseFutureDay(page: Page, days = 1) {
  for (let day = 0; day < days; day++) {
    const response = page.waitForResponse(
      (result) => result.url().includes("/availability?") && result.ok(),
    );
    await page.getByRole("button", { name: "Next day", exact: true }).click();
    await response;
  }
  await expect(
    page.getByRole("button", { name: "Refresh availability" }),
  ).toBeEnabled();
}

async function firstAvailable(page: Page) {
  const slot = page
    .getByRole("button", { name: /\d{2}:\d{2} to \d{2}:\d{2}, Available/i })
    .first();
  await expect(slot).toBeVisible();
  return slot;
}

async function cancelFromBookings(page: Page, roomName?: string) {
  await page.getByRole("button", { name: "My bookings", exact: true }).click();
  const card = roomName
    ? page
        .getByRole("article")
        .filter({
          has: page.getByRole("heading", { name: roomName, exact: true }),
        })
        .first()
    : page.getByRole("article").filter({ hasText: "Confirmed" }).first();
  await expect(card).toBeVisible();
  const reservationId = await card.getAttribute("data-reservation-id");
  expect(reservationId).toMatch(/^[0-9a-f-]{36}$/);
  const stableCard = page.locator(
    `article[data-reservation-id="${reservationId}"]`,
  );
  await card
    .getByRole("button", { name: "Cancel booking", exact: true })
    .click();
  const dialog = page.getByRole("dialog", {
    name: "Cancel this booking?",
    exact: true,
  });
  await dialog
    .getByRole("button", { name: "Cancel booking", exact: true })
    .click();
  await expect(dialog).toBeHidden();
  await expect(
    stableCard.getByText("Cancelled", { exact: true }),
  ).toBeVisible();
}

async function screenshot(page: Page, testInfo: TestInfo, name: string) {
  // Capture from the top so off-viewport fixed skip links do not appear in full-page stitching.
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: "instant" }));
  await page.screenshot({
    path: testInfo.outputPath(`${testInfo.project.name}-${name}.png`),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
}

test.beforeEach(async ({ request, page }) => {
  const marker = await request.get("/api/instance");
  expect(
    marker.ok(),
    "The parent must start the owned integrated Slotkeeper app before browser verification.",
  ).toBe(true);
  expect(await marker.json()).toMatchObject({
    project: "Slotkeeper",
    mode: "LOCAL",
    version: "0.1.0",
  });
  await page.addInitScript(() => {
    (
      window as Window & { slotkeeperCspViolations?: string[] }
    ).slotkeeperCspViolations = [];
    document.addEventListener("securitypolicyviolation", (event) => {
      (
        window as Window & { slotkeeperCspViolations?: string[] }
      ).slotkeeperCspViolations?.push(event.violatedDirective);
    });
  });
});

test.afterEach(async ({ page }) => {
  expect(
    await page.evaluate(
      () =>
        (window as Window & { slotkeeperCspViolations?: string[] })
          .slotkeeperCspViolations ?? [],
    ),
  ).toEqual([]);
});

test("member books, inspects and cancels a room", async ({
  page,
}, testInfo) => {
  await signIn(page, "alex");
  await expect(
    page.getByRole("button", { name: "Manage rooms", exact: true }),
  ).toHaveCount(0);
  await chooseFutureDay(page, testInfo.project.name === "desktop" ? 3 : 4);
  await (await firstAvailable(page)).click();
  await page
    .getByRole("button", { name: "Hold this time", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Confirm booking", exact: true }),
  ).toBeVisible();
  await screenshot(page, testInfo, "booking");
  await page
    .getByRole("button", { name: "Confirm booking", exact: true })
    .click();
  await expect(
    page.getByText("Your room is booked.", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "My bookings", exact: true }).click();
  const card = page
    .getByRole("article")
    .filter({ hasText: "Confirmed" })
    .first();
  await card.getByRole("button", { name: "View booking", exact: true }).click();
  const details = page.getByRole("dialog", {
    name: "Booking details",
    exact: true,
  });
  await expect(
    details.getByText("Booking reference", { exact: true }),
  ).toBeVisible();
  await details
    .getByRole("button", { name: "Close details", exact: true })
    .click();
  await cancelFromBookings(page);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Username", { exact: true })).toBeVisible();
});

test("admin creates and closes a room while its existing booking survives", async ({
  page,
}, testInfo) => {
  await signIn(page, "morgan");
  const name = `Review room ${testInfo.project.name} ${Date.now()}`;
  await page.getByRole("button", { name: "Manage rooms", exact: true }).click();
  await page.getByRole("button", { name: "Add room", exact: true }).click();
  const create = page.getByRole("dialog", { name: "Add a room", exact: true });
  await create
    .getByRole("textbox", { name: "Room name", exact: true })
    .fill(name);
  await create
    .getByRole("textbox", { name: "Description", exact: true })
    .fill("A synthetic room for the local browser journey.");
  await create
    .getByRole("button", { name: "Create room", exact: true })
    .click();
  await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Book a room", exact: true }).click();
  await page.getByRole("button", { name: new RegExp(name) }).click();
  await chooseFutureDay(page, testInfo.project.name === "desktop" ? 3 : 4);
  await (await firstAvailable(page)).click();
  await page
    .getByRole("button", { name: "Hold this time", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Confirm booking", exact: true })
    .click();
  await expect(
    page.getByText("Your room is booked.", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Manage rooms", exact: true }).click();
  await page.getByRole("button", { name: `Edit ${name}`, exact: true }).click();
  const edit = page.getByRole("dialog", { name: "Edit room", exact: true });
  await edit
    .getByRole("checkbox", { name: "Open for new bookings", exact: true })
    .uncheck();
  await edit.getByRole("button", { name: "Save changes", exact: true }).click();
  const room = page
    .getByRole("article")
    .filter({ has: page.getByRole("heading", { name, exact: true }) });
  await expect(
    room.getByText("Closed to new bookings", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Activity", exact: true }).click();
  await expect(
    page
      .getByRole("row")
      .filter({ hasText: name })
      .filter({ hasText: "Booking confirmed" }),
  ).toHaveCount(1);
  await screenshot(page, testInfo, "activity");
  await cancelFromBookings(page, name);
});

test("a stale last-slot selection shows conflict and refreshes availability", async ({
  page,
  browser,
}, testInfo) => {
  const other = await browser.newContext({
    baseURL: testInfo.project.use.baseURL ?? "http://127.0.0.1:4190",
  });
  const otherPage = await other.newPage();
  try {
    await signIn(page, "alex");
    await chooseFutureDay(page, 2);
    const slot = await firstAvailable(page);
    const label = await slot.getAttribute("aria-label");
    await slot.click();
    const selectedRoom = await page
      .locator('.room-option[aria-pressed="true"] strong')
      .textContent();
    await signIn(otherPage, "morgan");
    await otherPage
      .getByRole("button", { name: new RegExp(selectedRoom!) })
      .first()
      .click();
    await chooseFutureDay(otherPage, 2);
    await otherPage.getByRole("button", { name: label!, exact: true }).click();
    await otherPage
      .getByRole("button", { name: "Hold this time", exact: true })
      .click();
    await expect(
      otherPage.getByRole("button", { name: "Confirm booking", exact: true }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: "Hold this time", exact: true })
      .click();
    await expect(page.getByRole("alert")).toContainText("no longer available");
    await expect(
      page.getByRole("button", {
        name: label!.replace("Available", "On hold"),
        exact: true,
      }),
    ).toBeDisabled();
    await expect(
      page.getByRole("button", { name: "Hold this time", exact: true }),
    ).toHaveCount(0);
    await screenshot(page, testInfo, "conflict");
    await otherPage
      .getByRole("button", { name: "Release this hold", exact: true })
      .click();
    await otherPage
      .getByRole("dialog", { name: "Release this hold?", exact: true })
      .getByRole("button", { name: "Release hold", exact: true })
      .click();
    await expect(
      otherPage.getByText("Your booking has been cancelled.", { exact: true }),
    ).toBeVisible();
  } finally {
    await other.close();
  }
});

test("workspace selection applies Jordan’s role separately in each workspace", async ({
  page,
}, testInfo) => {
  await signIn(page, "jordan");
  const picker = page.getByLabel("Workspace", { exact: true });
  await picker.selectOption({ label: "Studio North" });
  await expect(
    page.getByRole("button", { name: "Manage rooms", exact: true }),
  ).toBeVisible();
  await chooseFutureDay(page);
  await (await firstAvailable(page)).click();
  const holdResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/holds") &&
      response.request().method() === "POST" &&
      response.ok(),
  );
  await page
    .getByRole("button", { name: "Hold this time", exact: true })
    .click();
  const studioHold = (await (await holdResponse).json()) as { id: string };
  await expect(
    page.getByRole("button", { name: "Confirm booking", exact: true }),
  ).toBeVisible();
  await picker.selectOption({ label: "Harbor Works" });
  await expect(
    page.getByRole("button", { name: "Manage rooms", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Activity", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Confirm booking", exact: true }),
  ).toHaveCount(0);
  const foreignBooking = await page.request.get(
    `/api/workspaces/${await picker.inputValue()}/reservations/${encodeURIComponent(studioHold.id)}`,
  );
  expect(foreignBooking.status()).toBe(404);
  expect(await foreignBooking.json()).not.toHaveProperty("roomId");
  await screenshot(page, testInfo, "workspace-member");
  await picker.selectOption({ label: "Studio North" });
  await page.getByRole("button", { name: "My bookings", exact: true }).click();
  const card = page.locator(`article[data-reservation-id="${studioHold.id}"]`);
  await expect(card.getByText("On hold", { exact: true })).toBeVisible();
  await card.getByRole("button", { name: "Release hold", exact: true }).click();
  await page
    .getByRole("dialog", { name: "Release this hold?", exact: true })
    .getByRole("button", { name: "Release hold", exact: true })
    .click();
  await expect(card.getByText("Cancelled", { exact: true })).toBeVisible();
});
