import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { scheduleFor, widgetSnapshot } from './widget-contract.mjs';

const fixture = JSON.parse(fs.readFileSync(new URL('../app/src/test/resources/widget-schedule-contract.json', import.meta.url), 'utf8'));
for (const sample of fixture.cases) {
  test(`widget schedule contract matches the web: ${sample.id}`, () => {
    const originalZone = process.env.TZ;
    try {
      process.env.TZ = sample.timezone;
      assert.deepEqual(JSON.parse(JSON.stringify(scheduleFor(sample.config).captureCurrentConfig())), sample.normalized);
      for (const check of sample.checks) assert.deepEqual(widgetSnapshot(sample.config, check.now), check.expected, check.now);
    } finally {
      if (originalZone === undefined) delete process.env.TZ;
      else process.env.TZ = originalZone;
    }
  });
}
