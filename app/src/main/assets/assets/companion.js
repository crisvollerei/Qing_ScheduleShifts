
export function createCompanion({window, ui, schedule}) {
  let companionTheme = '';
  let companionMessageId = 'status';
  let companionQuip = '';
  let companionTurn = 0;
  let companionFactIndex = 0;
  let companionQuipBag = [];
  let lastCompanionKey = '';
  const COMPANION_QUIPS = {
    light: [
      '今天的安排看过了，别忘了把自己也照顾好。',
      '能干不等于要硬撑，先松松肩膀。',
      '水杯就在旁边？很好，先喝一口。',
      '休息不是偷懒，是我批准的必要支出。',
      '今天做到这里也不错，不必事事满分。',
      '别急，一件一件来。我陪你理顺。',
      '不想说话也可以，这里给你留个位置。',
      '有点累就承认，龙也要收起翅膀歇一会儿。',
      '别只记着待办，午饭也算正事。',
      '你的努力我看见了。嗯，不是随口夸你。',
      '今天没那么顺？没关系，不必立刻振作。',
      '烦心事先放一边，不是每件都值得加班。',
      '这点小事还难不倒你，但也不用独自扛。',
      '给明天留点力气，不必今天就赢过所有人。',
      '偶尔依靠一下也无妨，别总抢着说没事。',
      '眼睛离开屏幕一会儿，远处也值得看看。',
      '不必拿最累的自己，和别人的高光比较。',
      '我珍藏的东西很多，安稳的小日子也算。',
      '今天的小进步，值得收进宝库。',
      '外套带上。别误会，只是顺手提醒。',
      '不用每次都表现得很厉害，普通的一天也好。',
      '饭要趁热吃，难题可以稍后再想。',
      '认真生活已经不易，对自己宽松一点。',
      '有些事明天再办，也不会让你少一分优秀。',
      '今天想抱怨几句？我听着，不急着讲道理。',
      '别把休息日排满，给喜欢的事留个空。',
      '被夸就收下，不许偷偷给自己扣分。',
      '你不用总有答案，先好好喘口气。',
      '今晚早点收工，月亮不负责替你补觉。',
      '别光顾着照顾别人，你的那份也留着。',
      '没什么大事，只是来看看你有没有好好吃饭。',
      '嗯，做得不错。再夸一句？下次吧。'
    ],
    dark: [
      '摸鱼雷达启动……先确认一下，水喝了吗？',
      '先吃饭再拯救世界，空着肚子容易卡住。',
      '我没偷懒，我在替你试坐休息区。',
      '叫谁大肥鱼呢……算了，分你一口点心。',
      '不是胖，是多装了一点好心情。',
      '饭可以多添一碗，工作可不可以少添一点？',
      '刚才想提醒你什么来着……哦，别饿着。',
      '外卖优惠券先放一边，你午饭吃了吗？',
      '今天的烦恼先靠岸，明天再决定要不要打捞。',
      '我负责偷偷摸鱼，你负责大大方方歇一会儿。',
      '夸我啦？咳，本女仆偶尔也很靠谱的。',
      '任务能不能外包再说，晚饭可不能外包给空气。',
      'Token省着点，点心可不能省。',
      '脑袋转不动了？我也一样，先伸个懒腰。',
      '有点委屈就说嘛，我边嚼边听也很认真的。',
      '做不完就先记着，别把自己也熬成鱼干。',
      '今天没什么精神？那就开省电模式。',
      '不是来催进度的，是来问你渴不渴。',
      '摸鱼也讲配合：你歇两分钟，我负责望风。',
      '好吃的记得趁热，消息可以晚一点回。',
      '我只是路过……顺便看看你有没有又忘吃饭。',
      '不开心的话，先把眉头松开一点点。',
      '今天已经够忙啦，别连休息都给自己打分。',
      '偷偷给你留了个靠岸的位置，不收点心费。',
      '打工可以认真，散步也可以认真呀。',
      '别逞强啦，废柴女仆也看得出你累了。',
      '如果快乐有库存，我想分你半碗。',
      '这会儿发个呆也好，我不会告状的。',
      '先把肩膀放下来，再决定要不要继续努力。',
      '饭要慢慢吃，日子也不用一口气过完。',
      '等下班就去找点好吃的，先把期待放在这儿。',
      '收到一次戳戳，返还一小口快乐。'
    ]
  };
  const { document, Date } = window;
  const { $, mk, animateElement, reveal } = ui;
  const { getShiftDef, getShift, addDays, getEffectiveShiftInfo, getNextWorkDelay, getDaysToRest } = schedule;
  function formatCompanionDuration(ms) {
    if (ms < 60000) return '不到1分钟';
    const minutes = Math.floor(ms / 60000), hours = Math.floor(minutes / 60), days = Math.floor(hours / 24);
    return `${days ? `${days}天` : ''}${hours % 24 ? `${hours % 24}小时` : ''}${minutes % 60 ? `${minutes % 60}分钟` : ''}`;
  }
  function companionFacts(now, info, next) {
    const night = companionTheme === 'dark';
    const working = info.start && now >= info.start && now < info.end;
    const facts = [working
      ? {id:'status',before:night ? '再过 ' : '离下班还有 ',time:formatCompanionDuration(info.end - now),after:night ? ' 就下班，先想想吃点什么。' : '，按自己的节奏来，别硬撑。'}
      : next !== null
        ? {id:'status',before:'距离下次上班还有 ',time:formatCompanionDuration(next),after:night ? '，歇一会儿，我替你望风。' : '，先把这段时间留给自己。'}
        : {id:'status',before:night ? '这轮排班没有工作班，安心歇着，饭还是要好好吃。' : '这轮排班没有工作班，照顾好自己就是正事。'}];
    const tomorrow = addDays(now,1), name = getShift(tomorrow), def = getShiftDef(name);
    facts.push(def.isWork
      ? {id:'tomorrow',before:`明天是「${name}」，`,time:def.startTime,after:night ? ' 开工，别空着肚子出门。' : ' 上班，提前留点余量，别赶。'}
      : {id:'tomorrow',before:`明天是「${name}」。${night ? '休息和加餐，我都举双手赞成。' : '把时间留给喜欢的事，也留给自己。'}`});
    const rest = getDaysToRest(now);
    if (rest === 0) facts.push(working
      ? {id:'rest',before:'今天的休息，要等这班结束。还有 ',time:formatCompanionDuration(info.end - now),after:night ? '，歇下来了记得吃点好的。' : '，歇下来就别急着忙下一件事。'}
      : {id:'rest',before:night ? '今天的排班是休息。放心摸鱼，饭点我可没忘。' : '今天的排班是休息。不必交出成果，歇着就好。'});
    else if (rest > 0) facts.push({id:'rest',before:'下个休息日在 ',time:`${rest}天后`,after:night ? '，先把想吃的记在小本本上。' : '，别排太满，给自己留点余地。'});
    return facts;
  }
  function updateCompanion(now = new Date(), info = getEffectiveShiftInfo(now), next = getNextWorkDelay(now)) {
    const facts = companionFacts(now,info,next);
    const message = companionMessageId === 'quip' ? {before:companionQuip} : facts.find(fact => fact.id === companionMessageId) || facts[0];
    const key = JSON.stringify(message);
    if (key === lastCompanionKey) return;
    lastCompanionKey = key;
    const parts = [document.createTextNode(message.before)];
    if (message.time) {
      const time = mk('strong',{cls:`companion-time${message.time.length > 10 ? ' is-long' : ''}`});
      for (const unit of message.time.match(/不到1分钟|\d+天后|\d+天|\d+小时|\d+分钟|\d{2}:\d{2}/g) || [message.time]) {
        time.append(mk('span',{cls:'companion-time-unit',text:unit}));
      }
      parts.push(time);
    }
    if (message.after) parts.push(document.createTextNode(message.after));
    $('companionMessage').replaceChildren(...parts);
    $('companion').dataset.message = companionMessageId;
  }
  function syncCompanionTheme(theme) {
    if (companionTheme === theme) return;
    companionTheme = theme;
    for (const image of document.querySelectorAll(`[data-image-theme="${theme}"]`)) {
      if (!image.hasAttribute('src')) image.src = image.dataset.src;
    }
    companionMessageId = 'status'; companionTurn = 0; companionFactIndex = 0; companionQuipBag = [];
    const name = theme === 'dark' ? '大肥鱼' : '大肥龙';
    $('companionName').textContent = name;
    $('companionAnnouncement').textContent = '';
    $('companionDay').hidden = theme !== 'light';
    $('companionNight').hidden = theme !== 'dark';
    $('companionMessage').scrollTop = 0;
    $('companionCharacter').setAttribute('aria-label',`戳戳${name}，换一句话`);
    updateCompanion();
  }
  function setCompanionCollapsed(collapsed) {
    $('companion').classList.toggle('is-collapsed',collapsed);
    $('companionBubble').hidden = collapsed;
    $('companionToggle').setAttribute('aria-expanded',String(!collapsed));
    $('companionToggle').setAttribute('aria-label',collapsed ? '展开碎碎念' : '收起碎碎念');
    $('companionToggle').replaceChildren(document.createTextNode(collapsed ? '聊两句 ' : '收起 '),mk('span',{text:collapsed ? '⌄' : '⌃'}));
    $('companionToggle').lastElementChild.setAttribute('aria-hidden','true');
  }
  function refreshCompanion() {
    setCompanionCollapsed(false);
    companionTurn++;
    if (companionTurn % 2) {
      if (!companionQuipBag.length) {
        companionQuipBag = [...COMPANION_QUIPS[companionTheme]];
        for (let i = companionQuipBag.length - 1; i > 0; i--) {
          const j = Math.floor(Math.random() * (i + 1));
          [companionQuipBag[i],companionQuipBag[j]] = [companionQuipBag[j],companionQuipBag[i]];
        }
        if (companionQuipBag[0] === companionQuip) companionQuipBag.push(companionQuipBag.shift());
      }
      companionMessageId = 'quip'; companionQuip = companionQuipBag.shift();
    } else {
      const now = new Date(), facts = companionFacts(now,getEffectiveShiftInfo(now),getNextWorkDelay(now));
      companionFactIndex = (companionFactIndex + 1) % facts.length;
      companionMessageId = facts[companionFactIndex].id;
    }
    updateCompanion();
    // Only intentional refreshes announce copy; live time changes stay quiet.
    $('companionMessage').scrollTop = 0;
    $('companionAnnouncement').textContent = `${$('companionName').textContent}说：${$('companionMessage').textContent}`;
    animateElement($('companionCharacter'),[
      {transform:'translateY(0) scale(1) rotate(0)'},
      {transform:'translateY(3px) scale(.91,1.04) rotate(-5deg)',offset:.24},
      {transform:'translateY(-8px) scale(1.04,.97) rotate(5deg)',offset:.55},
      {transform:'translateY(0) scale(1) rotate(0)'}
    ],{duration:440});
    for (const spark of document.querySelectorAll('.companion-spark')) animateElement(spark,[
      {opacity:0,transform:'translateY(6px) scale(.5)'},
      {opacity:1,transform:'translateY(-4px) scale(1.15)',offset:.4},
      {opacity:0,transform:'translateY(-20px) scale(.8)'}
    ],{duration:520});
    reveal($('companionMessage'));
  }
  $('companionCharacter').addEventListener('click',refreshCompanion);
  $('companionToggle').addEventListener('click',() => setCompanionCollapsed(!$('companionBubble').hidden));
  return { updateCompanion, syncCompanionTheme, companionFacts };
}
