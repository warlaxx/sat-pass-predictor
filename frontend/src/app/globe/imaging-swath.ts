/**
 * What a weather satellite images while it passes, for the few whose real-time downlink
 * an amateur station can actually receive and decode (SatDump, for example).
 *
 * A TLE says where a satellite is, not what it carries: the swath width is a property of
 * the instrument, so it is written down here by NORAD number. The widths are the
 * instrument's nominal ground swath (WMO OSCAR, operator sheets); the decoded image of a
 * given pass is shorter, because it ends where the station loses the signal.
 *
 * Deliberately left out: NOAA-15/18/19 (APT), decommissioned in 2025, and Metop-C, whose
 * AHRPT downlink is switched off — drawing a swath nobody can receive would mislead.
 */
export interface ImagingSwath {
  readonly instrument: string;
  readonly downlink: string;
  readonly widthKm: number;
}

const MSU_MR_LRPT: ImagingSwath = { instrument: 'MSU-MR', downlink: 'LRPT', widthKm: 2800 };
const AVHRR_AHRPT: ImagingSwath = { instrument: 'AVHRR', downlink: 'AHRPT', widthKm: 2900 };

const IMAGING_SWATHS: ReadonlyMap<number, ImagingSwath> = new Map([
  [57166, MSU_MR_LRPT], // Meteor-M N2-3
  [59051, MSU_MR_LRPT], // Meteor-M N2-4
  [38771, AVHRR_AHRPT], // Metop-B
]);

export function imagingSwathFor(noradId: number | undefined): ImagingSwath | undefined {
  return noradId === undefined ? undefined : IMAGING_SWATHS.get(noradId);
}
