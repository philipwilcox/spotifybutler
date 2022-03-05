import {ExternalUrls, Track} from "./track.js";
import {deserialize} from "cerialize";

export class PlaylistTrack {
    @deserialize added_at: Date;
    @deserialize added_by: AddedBy;
    @deserialize is_local: boolean;
    @deserialize primary_color: null | string;
    @deserialize track: Track;
    @deserialize video_thumbnail: VideoThumbnail;
}

export class AddedBy {
    @deserialize external_urls: ExternalUrls;
    @deserialize href: string;
    @deserialize id: string;
    @deserialize type: string;
    @deserialize uri: string;
    @deserialize name?: string;
}

export class VideoThumbnail {
    @deserialize url: null | string;
}
