// Only the slice of Node's `process` the e2e config and helpers read (no @types/node dependency).
declare const process: { env: Record<string, string | undefined> }
