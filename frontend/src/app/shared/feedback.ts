/**
 * Where the "give feedback" link of the development notice leads.
 *
 * An e-mail when the operator's address is published (`OPERATOR.email`): anyone can send
 * one, no account needed, and the subject and the page are filled in so that a message
 * says where it comes from. GitHub issues otherwise, the only channel left - a link to an
 * empty address would open a mail client with nowhere to send.
 */
export function feedbackHref(email: string, repositoryUrl: string, pageAddress: string): string {
  if (!email) return `${repositoryUrl}/issues`;
  const subject = $localize`:Subject of a feedback e-mail:NextPass feedback`;
  const body = $localize`:Body of a feedback e-mail, before the reader's message:Page: ${pageAddress}:address:` + '\n\n';
  return `mailto:${email}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
}
