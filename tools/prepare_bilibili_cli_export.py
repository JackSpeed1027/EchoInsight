"""文件职责：把已授权采集的 bilibili-cli 数据转成平台导入文件，弹幕与合集目录单独保留。"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path


def require(condition: bool, message: str) -> None:
    """拒绝身份或范围不明确的输入，不补造来源、正文或时间。"""
    if not condition:
        raise ValueError(message)


def integer(value: object, field: str, minimum: int = 1) -> int:
    """平台数字身份无损读取，布尔值和浮点数不可被 Python 的隐式转换接纳。"""
    require(type(value) is int and value >= minimum, f"{field} 必须为整数且不小于 {minimum}")
    return value


def text(value: object, field: str) -> str:
    """正文按原样保留，错误不回显私有文本。"""
    require(isinstance(value, str) and bool(value.strip()), f"{field} 必须为非空字符串")
    return value


def prepare(view: dict, roots: list, danmaku: list) -> dict[str, object]:
    """1、固定作品；2、展开已有回复；3、校验父关系；4、单独保存有时间轴的弹幕与合集。"""
    require(isinstance(view, dict) and view.get("code") == 0, "视频接口未成功")
    video = view.get("data")
    require(isinstance(video, dict), "缺少视频元数据")
    aid = str(integer(video.get("aid"), "aid"))
    bvid = text(video.get("bvid"), "bvid")
    pages = video.get("pages")
    require(isinstance(pages, list) and len(pages) == 1, "本轮桥接只支持单P，不能猜测弹幕属于哪一P")
    cid = str(integer(pages[0].get("cid"), "cid"))
    duration_ms = integer(pages[0].get("duration"), "duration") * 1000
    owner = video.get("owner") or {}
    creator = str(integer(owner.get("mid"), "owner.mid"))
    content = {
        "video_id": aid, "title": text(video.get("title"), "title"),
        "creator_hash": hashlib.sha256(("bilibili-owner:" + creator).encode()).hexdigest(),
        "create_time": integer(video.get("pubdate"), "pubdate"),
    }
    require(isinstance(roots, list) and 0 < len(roots) <= 5000, "需要 1～5000 条根评论记录")
    comments: dict[str, dict] = {}
    partial_threads = []

    def visit(row: dict, depth: int = 0) -> None:
        """展开 CLI 已经返回的回复；不自动补请求或删除缺失的父引用。"""
        require(isinstance(row, dict) and depth <= 8, "回复结构非法或过深")
        require(str(integer(row.get("oid"), "comment.oid")) == aid, "评论属于其他作品")
        require(row.get("type") == 1 and type(row.get("type")) is int, "只支持视频普通评论")
        source = str(integer(row.get("rpid"), "rpid"))
        parent = str(integer(row.get("parent"), "parent", 0))
        mapped = {"video_id": aid, "comment_id": source, "parent_comment_id": parent,
                  "content": text(row.get("content"), "content"),
                  "create_time": integer(row.get("ctime"), "ctime")}
        if source in comments:
            require(comments[source] == mapped, "相同评论身份有冲突版本")
        comments[source] = mapped
        require(len(comments) <= 5000, "展开后超过 5000 条")
        children = row.get("replies") or []
        require(isinstance(children, list), "replies 必须为数组")
        if "replies" in row and type(row.get("reply_count")) is int and row["reply_count"] > len(children):
            partial_threads.append({"commentId": source, "reportedReplyCount": row["reply_count"],
                                    "returnedDirectRows": len(children)})
        for child in children:
            visit(child, depth + 1)

    for row in roots:
        visit(row)
    for row in comments.values():
        require(row["parent_comment_id"] == "0" or row["parent_comment_id"] in comments, "缺少父评论，拒绝静默降级")
        seen = set()
        cursor = row["comment_id"]
        while cursor != "0":
            require(cursor not in seen, "父评论形成循环")
            seen.add(cursor)
            cursor = comments[cursor]["parent_comment_id"]

    # 弹幕只形成独立材料文件，不转换成普通评论，时间偏移始终绑定当前 cid。
    require(isinstance(danmaku, list) and len(danmaku) <= 5000, "弹幕输入必须为不超过 5000 条的数组")
    timed = []
    seen_dm = set()
    for row in danmaku:
        require(isinstance(row, dict), "弹幕必须为对象")
        dmid = str(integer(row.get("dmid"), "dmid"))
        require(dmid not in seen_dm, "弹幕身份重复")
        seen_dm.add(dmid)
        offset = integer(row.get("progress_ms"), "progress_ms", 0)
        require(offset <= duration_ms, "弹幕偏移超出视频时长")
        timed.append({"sourceType": "BILIBILI_DANMAKU", "bvid": bvid, "aid": aid, "cid": cid,
                      "sourceDanmakuId": dmid, "positionMs": offset,
                      "text": text(row.get("content"), "danmaku.content"),
                      "postedAtEpochSeconds": integer(row.get("ctime"), "danmaku.ctime")})

    season = video.get("ugc_season") or {}
    members = []
    for section in season.get("sections") or []:
        for episode in section.get("episodes") or []:
            members.append({"aid": str(integer(episode.get("aid"), "episode.aid")),
                            "bvid": text(episode.get("bvid"), "episode.bvid"),
                            "title": text(episode.get("title"), "episode.title")})
    manifest = {
        "bridgeVersion": "bilibili-cli-v0.3.0-to-mediacrawler-v1",
        "sourceTool": "https://github.com/tamnd/bilibili-cli",
        "bvid": bvid, "aid": aid, "cid": cid,
        "rootCommentRows": len(roots), "uniqueCommentRows": len(comments), "danmakuRows": len(timed),
        "commentsCompleteness": "NOT_PROVEN", "partiallyReturnedThreads": partial_threads,
        "sourceCoverage": None, "seasonId": str(season.get("id")) if season.get("id") else None,
        "seasonTitle": season.get("title"), "observedSeasonMembers": members,
        "scope": "仅当前链接作品；合集目录不代表已采集其他作品",
        "danmakuScope": "独立暂存，未进入评论统计或数据库分析",
    }
    return {"contents.json": [content], "comments.json": list(comments.values()),
            "danmaku.json": timed, "manifest.json": manifest}


def main() -> None:
    """本地转换工具不联网；输出目录必须全新，避免覆盖用户历史采集批次。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video-view", type=Path, required=True)
    parser.add_argument("--comments", type=Path, required=True)
    parser.add_argument("--danmaku", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    require(not args.output_dir.exists(), "输出目录已存在，请指定新的批次目录")
    sources = [args.video_view, args.comments, args.danmaku]
    for source in sources:
        require(source.is_file() and source.stat().st_size <= 5 * 1024 * 1024, "输入必须是最多 5 MiB 的本地文件")
    loaded = [json.loads(p.read_text(encoding="utf-8-sig")) for p in sources]
    converted = prepare(*loaded)
    manifest = converted["manifest.json"]
    manifest["preparedAt"] = datetime.now(timezone.utc).isoformat()
    manifest["sourceFiles"] = [{"name": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources]
    args.output_dir.mkdir(parents=True)
    for filename, value in converted.items():
        (args.output_dir / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: manifest[k] for k in ["bvid", "uniqueCommentRows", "danmakuRows", "commentsCompleteness"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
