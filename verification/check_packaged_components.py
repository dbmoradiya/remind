#!/usr/bin/env python3
"""Requires androguard. Validate executable manifest closure of the real APK."""
import sys,json
from loguru import logger
logger.remove()
from androguard.core.apk import APK
from androguard.core.dex import DEX
apk=APK(sys.argv[1]);classes={c.get_name():c for b in apk.get_all_dex() for c in DEX(b).get_classes()}
missing=[]
for name in apk.get_activities()+apk.get_receivers()+apk.get_services()+apk.get_providers():
 cls='L'+name.replace('.','/')+';'
 if cls not in classes:missing.append(name)
receiver=classes.get('Lcom/remind/app/widget/RemindWidgetReceiver;')
result={'version':apk.get_androidversion_name(),'versionCode':apk.get_androidversion_code(),'missing_components':missing,'receiver_super':receiver.get_superclassname() if receiver else None,'receiver_constructor': bool(receiver and any(m.get_name()=='<init>' for m in receiver.get_methods()))}
print(json.dumps(result,indent=2))
assert not missing,missing
assert result['receiver_super']=='Landroidx/glance/appwidget/GlanceAppWidgetReceiver;'
assert result['receiver_constructor']
