#!/usr/bin/env python3
"""Curated synthetic Chinese extraction cases; labels are authored, not model-generated."""
import json
from pathlib import Path
cases=[]
def add(text,action,**fields):
    issues=fields.pop('issues',[])
    expected={'schema_version':'1.0','action':action,**fields,'issues':issues}
    cases.append({'id':f'nlu-{len(cases)+1:03d}','text':text,'current_date':'2026-09-02','expected':expected,'source':'curated_synthetic'})
def up(text,item,location):add(text,'UPSERT_ITEM_INFO',item=item,location={'op':'SET' if location is not None else 'KEEP','value':location})
def inc(text,item,count,label,loc=None,date=None,source=None,issues=[]):add(text,'PROPOSE_ADD_UNITS',item=item,count=count,unit_label=label,location=loc,default_expiry=None if date is None else {'value':date,'source_text':source},issues=issues)
def op(text,item):add(text,'OPEN_ITEM',item=item)
def unknown(text):add(text,'UNKNOWN')
# Twenty balanced calibration examples, chosen before any model inference.
up('R8放防潮箱','R8','防潮箱');inc('增加三袋牛奶','牛奶',3,'袋');op('牛奶在哪','牛奶');unknown('你好')
up('AD200在器材柜','AD200','器材柜');inc('增加三袋牛奶，明天过期','牛奶',3,'袋',date='2026-09-03',source='明天');op('我要删除牛奶','牛奶');unknown('今天天气真好')
up('牛奶在桌子上','牛奶','桌子上');inc('买了一瓶可乐','可乐',1,'瓶');op('牛奶少了一袋','牛奶');unknown('打开微信')
up('我的钥匙放玄关柜','钥匙','玄关柜');inc('增加三袋牛奶放冰箱','牛奶',3,'袋','冰箱');op('AD200还有吗','AD200');unknown('播放音乐')
up('AD两百放器材柜','AD两百','器材柜');inc('新买两个苹果','苹果',2,'个');op('牛奶什么时候过期','牛奶');unknown('我饿了')
for text,item,loc in [
('R8在书桌上','R8','书桌上'),('AD200放器材柜','AD200','器材柜'),('牛奶在冰箱','牛奶','冰箱'),
('护照在书桌第二个抽屉','护照','书桌第二个抽屉'),('移动硬盘放黑色背包里','移动硬盘','黑色背包里'),
('R8放在防潮箱','R8','防潮箱'),('连花清瘟在放药的柜子里','连花清瘟','放药的柜子里'),
('莲花清瘟在药箱','莲花清瘟','药箱'),('把相机电池放在充电盒','相机电池','充电盒'),
('我的护照在行李箱夹层','护照','行李箱夹层'),('牙膏放浴室柜','牙膏','浴室柜'),
('纸巾在餐桌下','纸巾','餐桌下'),('眼镜放床头柜','眼镜','床头柜'),('雨伞放门后','雨伞','门后'),
('耳机在电脑包里','耳机','电脑包里'),('充电器在卧室抽屉','充电器','卧室抽屉'),('身份证放钱包里','身份证','钱包里'),
('书在书架最上层','书','书架最上层'),('剪刀放厨房左边抽屉','剪刀','厨房左边抽屉'),
('胶带在工具箱','胶带','工具箱'),('手电筒在鞋柜顶上','手电筒','鞋柜顶上'),('备用钥匙放红色盒子','备用钥匙','红色盒子'),
('电脑放工作台','电脑','工作台'),('XM5在床边','XM5','床边'),('XM五放在背包','XM五','背包'),
('R吧放防潮箱','R吧','防潮箱'),('七零到两百在摄影包','七零到两百','摄影包'),
('AD二百Pro放器材柜','AD二百Pro','器材柜'),('SD卡在读卡器旁边','SD卡','读卡器旁边'),
('记录一下相机','相机',None),('记住R8这个物品','R8',None),('记一下牛奶','牛奶',None),
('保温杯放办公桌右侧','保温杯','办公桌右侧'),('体温计在药箱第二层','体温计','药箱第二层'),
('螺丝刀在蓝色工具箱里','螺丝刀','蓝色工具箱里')]:up(text,item,loc)
for text,item,count,label,loc in [
('刚买三袋牛奶','牛奶',3,'袋',None),('增加两盒药','药',2,'盒',None),('增加三袋牛奶放桌子上','牛奶',3,'袋','桌子上'),
('买了两包纸巾','纸巾',2,'包',None),('新买一支牙膏','牙膏',1,'支',None),('增加五节电池','电池',5,'节',None),
('买入四个苹果','苹果',4,'个',None),('添加六瓶矿泉水','矿泉水',6,'瓶',None),('补充两袋大米','大米',2,'袋',None),
('添了一盒鸡蛋','鸡蛋',1,'盒',None),('增加十包饼干','饼干',10,'包',None),('买了十二瓶可乐','可乐',12,'瓶',None),
('添加2个相机电池','相机电池',2,'个',None),('新买3张SD卡','SD卡',3,'张',None),('增加一台AD200','AD200',1,'台',None),
('买了一台R8放防潮箱','R8',1,'台','防潮箱'),('增加一袋牛奶放桌子上','牛奶',1,'袋','桌子上'),
('增加两瓶洗发水放浴室柜','洗发水',2,'瓶','浴室柜'),('新买四包纸巾放储物柜','纸巾',4,'包','储物柜'),
('刚买一个保温杯放办公室','保温杯',1,'个','办公室'),('增加三盒口罩','口罩',3,'盒',None),
('添加一卷保鲜膜','保鲜膜',1,'卷',None),('买了两支笔','笔',2,'支',None),('买了五本笔记本','笔记本',5,'本',None),
('增加两根数据线','数据线',2,'根',None),('增加一台AD两百','AD两百',1,'台',None),('增加两副XM五','XM五',2,'副',None),
('补充三罐咖啡','咖啡',3,'罐',None),('新增一瓶酱油放厨房柜','酱油',1,'瓶','厨房柜'),('添加四个灯泡','灯泡',4,'个',None)]:inc(text,item,count,label,loc)
for text,item,count,label,date,source in [
('增加两袋牛奶，后天过期','牛奶',2,'袋','2026-09-04','后天'),
('增加一盒药，2026年12月31日过期','药',1,'盒','2026-12-31','2026年12月31日'),
('买了两盒酸奶，9月5日到期','酸奶',2,'盒','2026-09-05','9月5日'),
('添加三包面包，今天过期','面包',3,'包','2026-09-02','今天'),
('增加两瓶牛奶，三天后过期','牛奶',2,'瓶','2026-09-05','三天后'),
('新买一盒饼干，下周五过期','饼干',1,'盒','2026-09-11','下周五'),
('增加两盒药，2027-01-01到期','药',2,'盒','2027-01-01','2027-01-01')]:inc(text,item,count,label,date=date,source=source)
inc('三袋牛奶，一袋后天过期，两袋下周五过期','牛奶',3,'袋',issues=['AMBIGUOUS_DATE'])
for text,item in [
('牛奶还有多少','牛奶'),('看看牛奶','牛奶'),('打开牛奶','牛奶'),('喝掉一袋牛奶','牛奶'),('R8在哪','R8'),
('牛奶还有吗','牛奶'),('牛奶放哪里了','牛奶'),('帮我找钥匙','钥匙'),('护照放哪了','护照'),
('相机电池有几块','相机电池'),('纸巾还剩多少','纸巾'),('牙膏什么时候到期','牙膏'),
('可乐在哪儿','可乐'),('删除一瓶可乐','可乐'),('我用了一节电池','电池'),('我吃掉两个苹果','苹果'),
('我要删掉牙膏','牙膏'),('把牛奶删掉','牛奶'),('牛奶用完了','牛奶'),('查看纸巾','纸巾'),
('看一下药的到期时间','药'),('R吧在哪','R吧'),('AD两百在哪','AD两百'),('七零到两百还有吗','七零到两百'),
('XM五放哪里','XM五'),('莲花清瘟在哪','莲花清瘟'),('连花清瘟在哪','连花清瘟'),('AD200还有几台','AD200'),
('删除R8','R8'),('移动硬盘在哪','移动硬盘'),('耳机找不到了','耳机'),('我想看看保温杯','保温杯'),
('牛奶过期了吗','牛奶'),('牛奶减一袋','牛奶'),('拿走一个相机电池','相机电池'),('打开护照记录','护照'),
('查询SD卡','SD卡'),('列出牛奶库存','牛奶'),('饼干什么时候过期','饼干'),('口罩还剩几盒','口罩')]:op(text,item)
for text in ['谢谢','再见','你是谁','给我讲个故事','今天天气怎么样','帮我打开支付宝','我要听歌','放一首音乐','明天会下雨吗','现在几点了','我今天很开心','导航到公司','帮我打电话','设置明天七点的闹钟','请联网搜索新闻','你好呀','一加一等于几','今天好累','我的手机没电了','请翻译这句话'] : unknown(text)
# Fail-closed candidate cases: unresolved fields remain editable, no database effects.
add('增加牛奶','PROPOSE_ADD_UNITS',item='牛奶',count=None,unit_label=None,location=None,default_expiry=None,issues=['MISSING_COUNT'])
inc('增加零袋牛奶','牛奶',0,'袋',issues=['INVALID_COUNT'])
for i,c in enumerate(cases):c['split']='calibration' if i<20 else 'evaluation'
path=Path(__file__).resolve().parents[1]/'docs/nlu_benchmark_cases.json'
path.write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n');print(len(cases),path)
