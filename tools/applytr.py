"""按分组把 tr-*.json 的译文批量套到对应源文件上。用法：python tools/applytr.py tools/tr-batch3.json"""
import io
import json
import subprocess
import sys

BASE = 'src/main/java/com/alonediamond/playercontrolpp/'

# 分组名 -> 源文件相对路径
TARGETS = {
    'recmgr': 'record/RecordingManager.java',
    'routeexec': 'route/RouteExecutor.java',
    'tsm': 'feature/automaterial/TaskStateMachine.java',
    'opener': 'feature/automaterial/ContainerOpener.java',
    'itemexec': 'feature/automaterial/ItemTransferExecutor.java',
    'configs': 'config/Configs.java',
    'routemgr': 'route/RouteManager.java',
    'routegui': 'gui/RouteListGui.java',
    'route': 'route/Route.java',
    'recgui': 'gui/RecordingListGui.java',
    'gatherctx': 'feature/automaterial/GatherContext.java',
    'recfile': 'record/RecordingFile.java',
    'recorder': 'record/InputRecorder.java',
    'analyzer': 'feature/automaterial/MaterialAnalyzer.java',
    'runtime': 'route/RouteFlowRuntime.java',
    'pathctl': 'feature/automaterial/BaritonePathingController.java',
    'inputplayer': 'record/InputPlayer.java',
    'gatherer': 'feature/AutoMaterialGatherer.java',
    'init': 'config/InitHandler.java',
    'keycb': 'input/KeybindCallbacks.java',
    'shulker': 'feature/automaterial/ShulkerBoxStorage.java',
    'searcher': 'feature/automaterial/ContainerSearcher.java',
    'restock': 'feature/automaterial/AutoRestockFeature.java',
    'keyprov': 'input/KeybindProvider.java',
    'configgui': 'gui/PlayerControlppConfigGui.java',
    'segment': 'record/RecordedSegment.java',
    'keyframe': 'record/PositionKeyframe.java',
    'matentry': 'feature/automaterial/MaterialItemEntry.java',
    'waterfill': 'feature/AutoWaterFillFeature.java',
    'nearby': 'feature/AutoCacheNearbyContainersFeature.java',
    'schematic': 'feature/SchematicSelectionContainerCacheFeature.java',
    'quickshulker': 'integration/QuickShulkerIntegration.java',
}


def main():
    with io.open(sys.argv[1], encoding='utf-8') as f:
        groups = json.load(f)
    for key, mapping in groups.items():
        if key not in TARGETS:
            print('unknown group:', key)
            continue
        tmp = 'tools/_tmp.json'
        with io.open(tmp, 'w', encoding='utf-8') as f:
            json.dump(mapping, f, ensure_ascii=False)
        subprocess.call([sys.executable, 'tools/comments.py', 'apply', BASE + TARGETS[key], tmp])


if __name__ == '__main__':
    main()
