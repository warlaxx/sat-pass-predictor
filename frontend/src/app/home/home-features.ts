import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * The four views of a result, as the home page introduces them below the console, with
 * the engineering proof for those who look for it (ABD-38). Its own component since
 * ABD-19, to keep the home page's stylesheet under its budget.
 */
@Component({
  selector: 'app-home-features',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './home-features.scss',
  templateUrl: './home-features.html',
})
export class HomeFeatures {
  protected readonly features = [
    { title: $localize`Sky chart`, text: $localize`The pass drawn across your horizon, with the elevation threshold and the direction to face.` },
    { title: $localize`Globe`, text: $localize`Ground track coloured by sunlight on the satellite, the visibility circle and the imaging swath.` },
    { title: $localize`Elevation profile`, text: $localize`Elevation and range over time, with the Doppler shift when you give a downlink frequency.` },
    { title: $localize`Pass list`, text: $localize`Every pass in the window as a table, exportable to CSV and to your calendar as .ics.` },
  ] as const;
}
