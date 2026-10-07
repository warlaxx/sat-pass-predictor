import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OPERATOR, PRICING_ENABLED, REPOSITORY_URL, SWAGGER_URL } from '../shared/site';
import { LANGUAGES, addressIn, currentLanguage, pathIn } from '../shared/locale';
import { feedbackHref } from '../shared/feedback';
import { SITE_ORIGIN } from '../shared/seo';

/**
 * The footer under every page: the site map, the other language, the sources. Its own
 * component since ABD-19, so that the shell's stylesheet holds the header and the menu
 * only. The shell hands it the current address, which the other-language link and the
 * feedback address are made from.
 */
@Component({
  selector: 'app-site-footer',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './site-footer.scss',
  templateUrl: './site-footer.html',
})
export class SiteFooter {
  /** The router's address, as the shell tracks it. */
  readonly url = input.required<string>();

  protected readonly pricingEnabled = PRICING_ENABLED;
  protected readonly repositoryUrl = REPOSITORY_URL;
  protected readonly swaggerUrl = SWAGGER_URL;

  private readonly language = currentLanguage();
  /** The same page in the other language: a full load of the other build. */
  protected readonly otherLanguage = computed(() => {
    const other = LANGUAGES[this.language === 'fr' ? 'en' : 'fr'];
    return { ...other, href: addressIn(other.code, this.url()) };
  });

  /** True once an operator address is published: the footer then offers to write to us. */
  protected readonly feedbackByMail = Boolean(OPERATOR.email);
  protected readonly feedbackHref = computed(() =>
    feedbackHref(OPERATOR.email, REPOSITORY_URL, SITE_ORIGIN + pathIn(this.language, this.url())));
}
