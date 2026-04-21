export interface Product {
  id: string;
  name: string;
  description: string;
  priceUsd: number;
  category: 'telescope' | 'camera' | 'accessory';
}

export const CATALOG: readonly Product[] = [
  {
    id: 'tele-celestron-8',
    name: 'Celestron NexStar 8SE',
    description: '8-inch Schmidt-Cassegrain with computerized altazimuth mount.',
    priceUsd: 1299,
    category: 'telescope',
  },
  {
    id: 'tele-sky-dob-10',
    name: 'Sky-Watcher Dobsonian 10"',
    description: 'Classic 10-inch Dobsonian reflector, superb light grasp.',
    priceUsd: 749,
    category: 'telescope',
  },
  {
    id: 'cam-zwo-asi294',
    name: 'ZWO ASI294MC Pro',
    description: 'Cooled one-shot color astro camera, 4/3" sensor.',
    priceUsd: 1199,
    category: 'camera',
  },
  {
    id: 'acc-baader-zoom',
    name: 'Baader Hyperion Zoom 8-24mm',
    description: 'Premium zoom eyepiece for visual observing.',
    priceUsd: 249,
    category: 'accessory',
  },
  {
    id: 'acc-celestron-filter',
    name: 'Celestron UHC/LPR Filter 2"',
    description: 'Narrowband filter, boosts contrast on nebulae.',
    priceUsd: 79,
    category: 'accessory',
  },
];
