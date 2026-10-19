import Core from './schedule-core.js?v=fc384b3c941b';
import CalendarData from './calendar-data.js?v=3defcf37fd3d';
export const FESTIVAL_ART = Object.freeze({
  '元旦': {symbol:'new-year',palette:'gold',message:'新的一年，也把日常过得闪闪发光。'},
  '除夕': {symbol:'eve',palette:'coral',message:'一餐团圆，辞旧迎新。'},
  '春节': {symbol:'spring',palette:'coral',message:'灯火照新岁，愿诸事顺遂。'},
  '元宵节': {symbol:'lantern',palette:'coral',message:'花灯映圆月，日子暖而甜。'},
  '清明': {symbol:'qingming',palette:'jade',message:'风起清明，寄一份思念，赴一场春光。'},
  '劳动节': {symbol:'labor',palette:'blue',message:'每一份认真，都值得被看见。'},
  '端午节': {symbol:'dragon-boat',palette:'jade',message:'粽叶飘香，愿你端午安康。'},
  '中秋节': {symbol:'moon',palette:'gold',message:'月满人团圆，所念皆安好。'},
  '国庆节': {symbol:'national',palette:'coral',message:'山河锦绣，日常也有好风景。'},
  '重阳节': {symbol:'chrysanthemum',palette:'gold',message:'菊香秋意长，愿岁岁安康。'}
});

export function createFestivals({window, ui, schedule}) {
  const { document } = window;
  const { mk } = ui;
  const { formatDateISO } = schedule;
  function getSolarTerm(date) {
    const dates = CalendarData.dates[date.getFullYear()];
    if (!dates) return null;
    const index = dates.indexOf(formatDateISO(date).slice(5));
    return index >= 0 ? CalendarData.terms[index] : null;
  }
  function getHoliday(date) { return Core.festival(date, getSolarTerm(date)); }
  function festivalParts(festival) {
    return (festival || '').split('·').filter(name => Core.own(FESTIVAL_ART,name));
  }
  function makeFestivalArt(festival) {
    const ornament = mk('span',{cls:'festival-art'});
    ornament.setAttribute('aria-hidden','true');
    for (const name of festivalParts(festival)) {
      const svg = document.createElementNS('http://www.w3.org/2000/svg','svg');
      const use = document.createElementNS('http://www.w3.org/2000/svg','use');
      svg.setAttribute('viewBox','0 0 80 80');
      svg.setAttribute('focusable','false');
      use.setAttribute('href',`#festival-${FESTIVAL_ART[name].symbol}`);
      svg.append(use); ornament.append(svg);
    }
    return ornament;
  }
  function renderFestivalNotice(container, festival) {
    const parts = festivalParts(festival);
    container.hidden = !parts.length;
    container.replaceChildren();
    if (!parts.length) { delete container.dataset.festivalPalette; return; }
    container.dataset.festivalPalette = FESTIVAL_ART[parts[0]].palette;
    const copy = mk('div',{cls:'festival-copy'});
    copy.append(mk('strong',{text:festival}),mk('p',{text:parts.map(name => FESTIVAL_ART[name].message).join(' ')}));
    container.append(makeFestivalArt(festival),copy);
  }
  return { getSolarTerm, getHoliday, festivalParts, makeFestivalArt, renderFestivalNotice };
}
