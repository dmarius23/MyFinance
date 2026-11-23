// Registers jest-dom matchers (toBeInTheDocument, toHaveAttribute, …) on Vitest's `expect`.
import "@testing-library/jest-dom/vitest";

import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// `globals: false` means the runner does not auto-register RTL's cleanup, so do it here. Without
// this, mounted trees leak between tests and queries match elements from an earlier test.
afterEach(cleanup);
