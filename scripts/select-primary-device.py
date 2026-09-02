#!/usr/bin/env python3
"""Return the one authorized OPPO Find X8s serial; fail closed on absence or ambiguity."""
import argparse, subprocess, sys

p=argparse.ArgumentParser()
p.add_argument('--serial',help='Validate an explicitly supplied serial instead of selecting automatically')
a=p.parse_args()
rows=subprocess.check_output(['adb','devices','-l'],text=True).splitlines()[1:]
matches=[]
for row in rows:
    fields=row.split()
    if len(fields)<2 or fields[1]!='device' or (a.serial and fields[0]!=a.serial):
        continue
    serial=fields[0]
    def prop(key):
        return subprocess.check_output(['adb','-s',serial,'shell','getprop',key],text=True).strip()
    manufacturer=prop('ro.product.manufacturer')
    market=prop('ro.vendor.oplus.market.name') or prop('ro.vendor.oplus.market.enname')
    if manufacturer.casefold()=='oppo' and market.casefold()=='oppo find x8s':
        matches.append(serial)
if len(matches)!=1:
    sys.exit('Expected exactly one authorized OPPO Find X8s. Connect/unlock it and approve USB debugging; use --serial if more than one is connected.')
print(matches[0])
