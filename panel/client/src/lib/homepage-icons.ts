import type { ComponentType } from 'react';
import {
  Activity, AlertCircle, AlertTriangle, ArrowRight, Award, BarChart, Bell, Book, BookOpen, Bookmark, Calendar, Check,
  CheckCircle, ChevronRight, Clock, Cog, Compass, CreditCard, Crown, DollarSign, Download, ExternalLink, Eye, FileText,
  Filter, Flag, Gamepad2, Gift, Globe, GraduationCap, Headphones, Heart, HelpCircle, Home, Inbox, Info, Key, Library,
  Link as LinkIcon, Lock, Mail, Map as MapIcon, Megaphone, MessageCircle, MessageSquare, Mic, Minus, Navigation, Newspaper,
  Pause, Phone, Play, Plus, Radio, Scale, ScrollText, Search, Send, Server, Settings, Share, Shield, ShieldAlert,
  ShieldCheck, Signal, Sliders, Star, Tag, Target, ThumbsUp, Timer, TrendingUp, Trophy, Unlock, Upload, User, UserCheck,
  UserPlus, UserX, Users, Video, Wifi, Wrench, X, XCircle, Zap,
} from 'lucide-react';

export type HomepageIcon = ComponentType<{ className?: string; 'aria-hidden'?: boolean; 'data-modl'?: string }>;

export const DEFAULT_HOMEPAGE_ICON: HomepageIcon = BookOpen;

const HOMEPAGE_ICONS: ReadonlyMap<string, HomepageIcon> = new Map<string, HomepageIcon>(Object.entries({
  Shield, UserPlus, MessageCircle, Mail, Phone, Scale,
  Users, User, UserCheck, UserX, Crown, Award,
  BookOpen, Book, FileText, ScrollText, Newspaper,
  Library, GraduationCap, HelpCircle, Info, AlertCircle,
  ExternalLink, Link: LinkIcon, ArrowRight, ChevronRight, Home,
  Search, Eye, Download, Upload, Share,
  MessageSquare, Send, Inbox, Bell, Megaphone,
  Radio, Headphones, Mic, Video, Calendar,
  Gamepad2, Zap, Server, Globe, Wifi, Signal,
  Activity, BarChart, TrendingUp, Target, Trophy,
  Settings, Wrench, Cog, Sliders, Filter,
  Lock, Unlock, Key, ShieldCheck, ShieldAlert,
  Plus, Minus, Check, X, AlertTriangle, CheckCircle,
  XCircle, Clock, Timer, Pause, Play,
  CreditCard, DollarSign, Gift, Star, Heart, ThumbsUp,
  Flag, Map: MapIcon, Compass, Navigation, Bookmark, Tag,
}));

export const HOMEPAGE_ICON_NAMES: readonly string[] = Array.from(HOMEPAGE_ICONS.keys()).sort();

export function homepageIcon(name: string): HomepageIcon {
  return HOMEPAGE_ICONS.get(name) ?? DEFAULT_HOMEPAGE_ICON;
}
