export function isEmail(value) {
  return /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(
    String(value ?? "").trim()
  );
}
export function validLoginAccount(value, type) {
  return (
    isEmail(value) ||
    (type === 1 && /^1[3-9]\d{9}$/.test(String(value ?? "").trim()))
  );
}
