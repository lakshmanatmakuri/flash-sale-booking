/** One key per user intent. Generate when the user starts the action, reuse on every retry of THAT action. */
export function newIdempotencyKey(): string {
    return crypto.randomUUID()
}