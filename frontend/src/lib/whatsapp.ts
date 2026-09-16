/**
 * Build a wa.me "click-to-chat" URL that opens the WhatsApp desktop app (or WhatsApp Web) with the chat
 * for {@code phone} open and {@code text} pre-filled, ready for the user to review and send manually.
 * @see https://faq.whatsapp.com/5913398998672934
 */
export function waMeUrl(phone: string, text: string): string {
  const digits = waPhoneDigits(phone);
  return `https://wa.me/${digits}?text=${encodeURIComponent(text)}`;
}

/**
 * Normalize a phone to the international digits wa.me expects (country code + number, no "+", no spaces).
 * Mirrors the backend's default-country handling (Romania, +40): a national {@code 07…} becomes
 * {@code 407…}; {@code 0040…}/{@code +40…} keep their country code; anything already international is kept.
 */
export function waPhoneDigits(phone: string): string {
  let p = (phone ?? "").replace(/[\s.\-()]/g, "");
  if (p.startsWith("+")) {
    p = p.slice(1);
  } else if (p.startsWith("0040")) {
    p = p.slice(2); // 0040XXXXXXXXX -> 40XXXXXXXXX
  } else if (p.startsWith("0")) {
    p = "40" + p.slice(1); // national 07XXXXXXXX -> 407XXXXXXXX
  }
  return p.replace(/\D/g, "");
}
