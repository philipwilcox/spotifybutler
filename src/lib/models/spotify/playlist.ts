import {ExternalUrls, Image} from "./track";
import {deserialize} from "cerialize";
import {Followers} from "./artist.js";

export class Playlist {
    @deserialize collaborative: boolean;
    @deserialize description: string;
    @deserialize external_urls: ExternalUrls;
    @deserialize followers: Followers;
    @deserialize href: string;
    @deserialize id: string;
    @deserialize images: Image[];
    @deserialize name: string;
    @deserialize owner: Owner;
    @deserialize primary_color: null | string;
    @deserialize public: boolean;
    @deserialize snapshot_id: string;
    @deserialize tracks: TracksReference;
    @deserialize type: string;
    @deserialize uri: string;
}


export class Owner {
    @deserialize display_name: string;
    @deserialize external_urls: ExternalUrls;
    @deserialize href: string;
    @deserialize id: string;
    @deserialize type: string;
    @deserialize uri: string;
}

export class TracksReference {
    @deserialize href: string;
    @deserialize total: number;
}