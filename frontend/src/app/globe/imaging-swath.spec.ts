import { describe, expect, it } from 'vitest';
import { imagingSwathFor } from './imaging-swath';

describe('imagingSwathFor', () => {
  it('knows the receivable Meteor-M LRPT and Metop-B AHRPT imagers', () => {
    expect(imagingSwathFor(57166)).toEqual({ instrument: 'MSU-MR', downlink: 'LRPT', widthKm: 2800 });
    expect(imagingSwathFor(59051)?.downlink).toBe('LRPT');
    expect(imagingSwathFor(38771)).toEqual({ instrument: 'AVHRR', downlink: 'AHRPT', widthKm: 2900 });
  });
  it('draws no swath for other satellites, nor for decommissioned NOAA APT ones', () => {
    expect(imagingSwathFor(25544)).toBeUndefined();
    expect(imagingSwathFor(33591)).toBeUndefined();
    expect(imagingSwathFor(undefined)).toBeUndefined();
  });
});
