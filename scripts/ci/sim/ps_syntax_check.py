import sys
p='scripts/ci/sim/test-path-determinism.ps1'
s=open(p,'r',encoding='utf-8').read()
# Quick checks
print('Length',len(s))
print('Double quotes',s.count('"'))
print('Single quotes',s.count("'"))
print('Open paren',s.count('('),'Close paren',s.count(')'))
print('Open brace',s.count('{'),'Close brace',s.count('}'))
print('Open bracket',s.count('['),'Close bracket',s.count(']'))
# Show problematic lines around any mismatches
lines=s.splitlines()
for i,l in enumerate(lines,1):
    if 260 <= i <= 360:
        print(i, 'dq=', l.count('"'), 'sq=', l.count("'"), l)

# Attempt to detect unbalanced quotes per line
for i,l in enumerate(lines,1):
    dq=l.count('"')
    sq=l.count("'")
    if dq%2!=0 or sq%2!=0:
        print('Unbalanced quotes at',i, 'dq=',dq,'sq=',sq, l)
        break

# Find any line with $k: pattern
for i,l in enumerate(lines,1):
    if '$k:' in l:
        print('Found $k: at',i,l)
        break
