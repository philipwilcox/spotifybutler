import {ExternalUrls, Image} from "./track";
import {deserialize} from "cerialize";

export class Playlist {
    @deserialize collaborative: boolean;
    @deserialize description: string;
    @deserialize external_urls: ExternalUrls;
    @deserialize href: string;
    @deserialize id: string;
    @deserialize images: Image[];
    @deserialize name: string;
    @deserialize owner: Owner;
    @deserialize primary_color: null;
    @deserialize public: boolean;
    @deserialize snapshot_id: string;
    @deserialize tracks: Tracks;
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

export class Tracks {
    @deserialize href: string;
    @deserialize total: number;
}